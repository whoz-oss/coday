package io.whozoss.agentos.agent

import io.kotest.assertions.throwables.shouldThrow
import io.kotest.core.spec.style.StringSpec
import io.kotest.matchers.shouldBe
import io.kotest.matchers.string.shouldContain
import io.kotest.matchers.string.shouldNotContain
import io.mockk.every
import io.mockk.mockk
import io.mockk.verify
import io.whozoss.agentos.aiModel.AiModelRegistry
import io.whozoss.agentos.chat.ChatClientProvider
import io.whozoss.agentos.integrationConfig.IntegrationConfig
import io.whozoss.agentos.integrationConfig.IntegrationConfigService
import io.whozoss.agentos.namespace.Namespace
import io.whozoss.agentos.namespace.NamespaceService
import io.whozoss.agentos.sdk.aiProvider.AiModel
import io.whozoss.agentos.sdk.entity.EntityMetadata
import io.whozoss.agentos.tool.ToolRegistryService
import io.whozoss.agentos.user.User
import io.whozoss.agentos.user.UserService
import org.springframework.ai.chat.client.ChatClient
import java.util.UUID

class AgentServiceImplSpec : StringSpec() {
    private val chatClientProvider: ChatClientProvider = mockk()
    private val toolRegistryService: ToolRegistryService = mockk()
    private val aiModelRegistry: AiModelRegistry = mockk()
    private val namespaceService: NamespaceService = mockk()
    private val integrationConfigService: IntegrationConfigService = mockk()
    private val userService: UserService = mockk(relaxed = true)
    private val agentService = AgentServiceImpl(chatClientProvider, toolRegistryService, aiModelRegistry, namespaceService, integrationConfigService, userService)

    // A context and matching namespace used across most tests
    private val namespaceId: UUID = UUID.randomUUID()
    private val caseId: UUID = UUID.randomUUID()
    private val context = AgentExecutionContext(namespaceId = namespaceId, caseId = caseId)
    private val namespace =
        Namespace(
            metadata = EntityMetadata(id = namespaceId),
            name = "engineering",
            description = "Engineering namespace for backend services",
        )

    init {
        every { toolRegistryService.resolveToolsForNamespace(any()) } returns emptyList()
        every { namespaceService.findById(namespaceId) } returns namespace
        every { integrationConfigService.findByParent(namespaceId) } returns emptyList()

        // The key scenario: name and modelName are intentionally different.
        // "my-agent" is the logical name used to look up the ChatClient,
        // while "gpt-4o-2024-08-06" is the underlying provider model identifier.
        // The bug was using model.modelName ("gpt-4o-2024-08-06") instead of
        // model.name ("my-agent"), causing a lookup failure in ChatClientProvider.
        fun modelWithDistinctNameAndModelName() =
            AiModel(
                metadata = EntityMetadata(id = UUID.randomUUID()),
                name = "my-agent",
                description = "An agent whose name differs from its provider model identifier",
                modelName = "gpt-4o-2024-08-06",
                providerName = "openai",
            )

        // -------------------------------------------------------------------------
        // findAgentByName
        // -------------------------------------------------------------------------

        "findAgentByName calls getChatClient with model.name, not model.modelName" {
            val model = modelWithDistinctNameAndModelName()
            val chatClient = mockk<ChatClient>(relaxed = true)

            every { aiModelRegistry.findByName("my-agent") } returns model
            every { chatClientProvider.getChatClient("my-agent") } returns chatClient

            agentService.findAgentByName("my-agent", context)

            verify(exactly = 1) { chatClientProvider.getChatClient("my-agent") }
            verify(exactly = 0) { chatClientProvider.getChatClient("gpt-4o-2024-08-06") }
        }

        "findAgentByName falls back to contains-match and still uses model.name for getChatClient" {
            val model = modelWithDistinctNameAndModelName()
            val chatClient = mockk<ChatClient>(relaxed = true)

            every { aiModelRegistry.findByName("agent") } returns null
            every { aiModelRegistry.getAll() } returns listOf(model)
            every { chatClientProvider.getChatClient("my-agent") } returns chatClient

            agentService.findAgentByName("agent", context)

            verify(exactly = 1) { chatClientProvider.getChatClient("my-agent") }
            verify(exactly = 0) { chatClientProvider.getChatClient("gpt-4o-2024-08-06") }
        }

        "findAgentByName throws when no model matches the given name" {
            every { aiModelRegistry.findByName("unknown") } returns null
            every { aiModelRegistry.getAll() } returns emptyList()

            shouldThrow<IllegalArgumentException> {
                agentService.findAgentByName("unknown", context)
            }
        }

        // -------------------------------------------------------------------------
        // Namespace context injection into instructions
        // -------------------------------------------------------------------------

        "findAgentByName appends namespace name and description to existing model instructions" {
            val model =
                AiModel(
                    metadata = EntityMetadata(id = UUID.randomUUID()),
                    name = "my-agent",
                    description = "desc",
                    modelName = "gpt-4o",
                    providerName = "openai",
                    instructions = "You are a helpful assistant.",
                )
            val chatClient = mockk<ChatClient>(relaxed = true)
            every { aiModelRegistry.findByName("my-agent") } returns model
            every { chatClientProvider.getChatClient("my-agent") } returns chatClient

            val agent = agentService.findAgentByName("my-agent", context) as AgentSimple

            agent.instructions!! shouldContain "You are a helpful assistant."
            agent.instructions!! shouldContain namespace.name
            agent.instructions!! shouldContain namespace.description!!
        }

        "findAgentByName uses namespace context as sole instructions when model has none" {
            val model =
                AiModel(
                    metadata = EntityMetadata(id = UUID.randomUUID()),
                    name = "my-agent",
                    description = "desc",
                    modelName = "gpt-4o",
                    providerName = "openai",
                    instructions = null,
                )
            val chatClient = mockk<ChatClient>(relaxed = true)
            every { aiModelRegistry.findByName("my-agent") } returns model
            every { chatClientProvider.getChatClient("my-agent") } returns chatClient

            val agent = agentService.findAgentByName("my-agent", context) as AgentSimple

            agent.instructions!! shouldContain namespace.name
            agent.instructions!! shouldContain namespace.description!!
        }

        "findAgentByName includes namespace name but not a blank description when namespace has no description" {
            val namespaceWithoutDescription =
                Namespace(
                    metadata = EntityMetadata(id = namespaceId),
                    name = "engineering",
                    description = null,
                )
            every { namespaceService.findById(namespaceId) } returns namespaceWithoutDescription
            val model =
                AiModel(
                    metadata = EntityMetadata(id = UUID.randomUUID()),
                    name = "my-agent",
                    description = "desc",
                    modelName = "gpt-4o",
                    providerName = "openai",
                    instructions = "Base instructions.",
                )
            val chatClient = mockk<ChatClient>(relaxed = true)
            every { aiModelRegistry.findByName("my-agent") } returns model
            every { chatClientProvider.getChatClient("my-agent") } returns chatClient

            val agent = agentService.findAgentByName("my-agent", context) as AgentSimple

            agent.instructions!! shouldContain "engineering"
            agent.instructions!! shouldNotContain "null"
            // Restore the default stub for subsequent tests
            every { namespaceService.findById(namespaceId) } returns namespace
        }

        // -------------------------------------------------------------------------
        // Integration descriptions block injection into instructions
        // -------------------------------------------------------------------------

        "findAgentByName appends integrations block when configs have descriptions" {
            val configs = listOf(
                IntegrationConfig(
                    metadata = EntityMetadata(id = UUID.randomUUID()),
                    namespaceId = namespaceId,
                    name = "JIRA_PROD",
                    integrationType = "JIRA",
                    description = "Production Jira workspace for the engineering team",
                ),
                IntegrationConfig(
                    metadata = EntityMetadata(id = UUID.randomUUID()),
                    namespaceId = namespaceId,
                    name = "SLACK_DEV",
                    integrationType = "SLACK",
                    description = "Dev Slack channel for notifications",
                ),
            )
            every { integrationConfigService.findByParent(namespaceId) } returns configs
            val model = AiModel(
                metadata = EntityMetadata(id = UUID.randomUUID()),
                name = "my-agent",
                description = "desc",
                modelName = "gpt-4o",
                providerName = "openai",
                instructions = "You are a helpful assistant.",
            )
            val chatClient = mockk<ChatClient>(relaxed = true)
            every { aiModelRegistry.findByName("my-agent") } returns model
            every { chatClientProvider.getChatClient("my-agent") } returns chatClient

            val agent = agentService.findAgentByName("my-agent", context) as AgentSimple

            agent.instructions!! shouldContain "## Integrations"
            agent.instructions!! shouldContain "JIRA_PROD: Production Jira workspace for the engineering team"
            agent.instructions!! shouldContain "SLACK_DEV: Dev Slack channel for notifications"
            // Restore default stub
            every { integrationConfigService.findByParent(namespaceId) } returns emptyList()
        }

        "findAgentByName omits integrations block when no config has a description" {
            val configs = listOf(
                IntegrationConfig(
                    metadata = EntityMetadata(id = UUID.randomUUID()),
                    namespaceId = namespaceId,
                    name = "JIRA_PROD",
                    integrationType = "JIRA",
                    description = null,
                ),
            )
            every { integrationConfigService.findByParent(namespaceId) } returns configs
            val model = AiModel(
                metadata = EntityMetadata(id = UUID.randomUUID()),
                name = "my-agent",
                description = "desc",
                modelName = "gpt-4o",
                providerName = "openai",
                instructions = "You are a helpful assistant.",
            )
            val chatClient = mockk<ChatClient>(relaxed = true)
            every { aiModelRegistry.findByName("my-agent") } returns model
            every { chatClientProvider.getChatClient("my-agent") } returns chatClient

            val agent = agentService.findAgentByName("my-agent", context) as AgentSimple

            agent.instructions!! shouldNotContain "## Integrations"
            // Restore default stub
            every { integrationConfigService.findByParent(namespaceId) } returns emptyList()
        }

        "findAgentByName only lists configs that have a description in the integrations block" {
            val configs = listOf(
                IntegrationConfig(
                    metadata = EntityMetadata(id = UUID.randomUUID()),
                    namespaceId = namespaceId,
                    name = "JIRA_PROD",
                    integrationType = "JIRA",
                    description = "Production Jira",
                ),
                IntegrationConfig(
                    metadata = EntityMetadata(id = UUID.randomUUID()),
                    namespaceId = namespaceId,
                    name = "GITHUB_MAIN",
                    integrationType = "GITHUB",
                    description = null,
                ),
            )
            every { integrationConfigService.findByParent(namespaceId) } returns configs
            val model = AiModel(
                metadata = EntityMetadata(id = UUID.randomUUID()),
                name = "my-agent",
                description = "desc",
                modelName = "gpt-4o",
                providerName = "openai",
                instructions = null,
            )
            val chatClient = mockk<ChatClient>(relaxed = true)
            every { aiModelRegistry.findByName("my-agent") } returns model
            every { chatClientProvider.getChatClient("my-agent") } returns chatClient

            val agent = agentService.findAgentByName("my-agent", context) as AgentSimple

            agent.instructions!! shouldContain "JIRA_PROD: Production Jira"
            agent.instructions!! shouldNotContain "GITHUB_MAIN"
            // Restore default stub
            every { integrationConfigService.findByParent(namespaceId) } returns emptyList()
        }

        "findAgentByName omits integrations block when namespace has no configs" {
            val model = AiModel(
                metadata = EntityMetadata(id = UUID.randomUUID()),
                name = "my-agent",
                description = "desc",
                modelName = "gpt-4o",
                providerName = "openai",
                instructions = null,
            )
            val chatClient = mockk<ChatClient>(relaxed = true)
            every { aiModelRegistry.findByName("my-agent") } returns model
            every { chatClientProvider.getChatClient("my-agent") } returns chatClient
            // integrationConfigService returns emptyList() by default

            val agent = agentService.findAgentByName("my-agent", context) as AgentSimple

            agent.instructions!! shouldNotContain "## Integrations"
        }

        "findAgentByName resolves namespace by namespaceId from context" {
            val model = modelWithDistinctNameAndModelName()
            val chatClient = mockk<ChatClient>(relaxed = true)
            every { aiModelRegistry.findByName("my-agent") } returns model
            every { chatClientProvider.getChatClient("my-agent") } returns chatClient

            agentService.findAgentByName("my-agent", context)

            verify(exactly = 1) { namespaceService.findById(namespaceId) }
        }

        // -------------------------------------------------------------------------
        // User context injection into instructions
        // -------------------------------------------------------------------------

        "findAgentByName appends user fields to instructions when userId resolves a known user" {
            val userId = UUID.randomUUID()
            val user = User(
                metadata = EntityMetadata(id = userId),
                externalId = "ext-123",
                email = "alice@example.com",
                firstname = "Alice",
                lastname = "Smith",
                bio = "Backend engineer passionate about distributed systems.",
            )
            val contextWithUser = AgentExecutionContext(namespaceId = namespaceId, caseId = caseId, userId = userId)
            val model = AiModel(
                metadata = EntityMetadata(id = UUID.randomUUID()),
                name = "my-agent",
                description = "desc",
                modelName = "gpt-4o",
                providerName = "openai",
                instructions = "You are a helpful assistant.",
            )
            val chatClient = mockk<ChatClient>(relaxed = true)
            every { aiModelRegistry.findByName("my-agent") } returns model
            every { chatClientProvider.getChatClient("my-agent") } returns chatClient
            every { userService.findById(userId) } returns user

            val agent = agentService.findAgentByName("my-agent", contextWithUser) as AgentSimple

            agent.instructions!! shouldContain user.email
            agent.instructions!! shouldContain user.firstname!!
            agent.instructions!! shouldContain user.lastname!!
            agent.instructions!! shouldContain user.bio!!
            agent.instructions!! shouldContain userId.toString()
        }

        "findAgentByName omits optional user fields that are blank" {
            val userId = UUID.randomUUID()
            val user = User(
                metadata = EntityMetadata(id = userId),
                externalId = "ext-456",
                email = "bob@example.com",
                firstname = null,
                lastname = null,
                bio = null,
            )
            val contextWithUser = AgentExecutionContext(namespaceId = namespaceId, caseId = caseId, userId = userId)
            val model = AiModel(
                metadata = EntityMetadata(id = UUID.randomUUID()),
                name = "my-agent",
                description = "desc",
                modelName = "gpt-4o",
                providerName = "openai",
                instructions = null,
            )
            val chatClient = mockk<ChatClient>(relaxed = true)
            every { aiModelRegistry.findByName("my-agent") } returns model
            every { chatClientProvider.getChatClient("my-agent") } returns chatClient
            every { userService.findById(userId) } returns user

            val agent = agentService.findAgentByName("my-agent", contextWithUser) as AgentSimple

            agent.instructions!! shouldContain user.email
            agent.instructions!! shouldNotContain "firstname"
            agent.instructions!! shouldNotContain "lastname"
            agent.instructions!! shouldNotContain "bio"
        }

        "findAgentByName skips user block when userId is null" {
            val model = AiModel(
                metadata = EntityMetadata(id = UUID.randomUUID()),
                name = "my-agent",
                description = "desc",
                modelName = "gpt-4o",
                providerName = "openai",
                instructions = "Base instructions.",
            )
            val chatClient = mockk<ChatClient>(relaxed = true)
            every { aiModelRegistry.findByName("my-agent") } returns model
            every { chatClientProvider.getChatClient("my-agent") } returns chatClient
            // context has no userId (default null)

            val agent = agentService.findAgentByName("my-agent", context) as AgentSimple

            agent.instructions!! shouldNotContain "## User"
            verify(exactly = 0) { userService.findById(any()) }
        }

        "findAgentByName skips user block when userId does not resolve to a known user" {
            val unknownUserId = UUID.randomUUID()
            val contextWithUnknownUser = AgentExecutionContext(namespaceId = namespaceId, caseId = caseId, userId = unknownUserId)
            val model = AiModel(
                metadata = EntityMetadata(id = UUID.randomUUID()),
                name = "my-agent",
                description = "desc",
                modelName = "gpt-4o",
                providerName = "openai",
                instructions = "Base instructions.",
            )
            val chatClient = mockk<ChatClient>(relaxed = true)
            every { aiModelRegistry.findByName("my-agent") } returns model
            every { chatClientProvider.getChatClient("my-agent") } returns chatClient
            every { userService.findById(unknownUserId) } returns null

            val agent = agentService.findAgentByName("my-agent", contextWithUnknownUser) as AgentSimple

            agent.instructions!! shouldNotContain "## User"
            agent.instructions!! shouldContain "Base instructions."
        }

        // -------------------------------------------------------------------------
        // getDefaultAgent
        // -------------------------------------------------------------------------

        "getDefaultAgent returns null when no default model is registered" {
            every { aiModelRegistry.getDefault() } returns null

            agentService.getDefaultAgent(context) shouldBe null
        }

        "getDefaultAgent calls getChatClient with model.name for the default model" {
            val model = modelWithDistinctNameAndModelName()
            val chatClient = mockk<ChatClient>(relaxed = true)

            every { aiModelRegistry.getDefault() } returns model
            every { chatClientProvider.getChatClient("my-agent") } returns chatClient

            agentService.getDefaultAgent(context)

            verify(exactly = 1) { chatClientProvider.getChatClient("my-agent") }
            verify(exactly = 0) { chatClientProvider.getChatClient("gpt-4o-2024-08-06") }
        }

        // -------------------------------------------------------------------------
        // getDefaultAgentName
        // -------------------------------------------------------------------------

        "getDefaultAgentName returns null when no default model is registered" {
            every { aiModelRegistry.getDefault() } returns null

            agentService.getDefaultAgentName() shouldBe null
        }

        "getDefaultAgentName returns the model name without instantiating any agent" {
            val model = modelWithDistinctNameAndModelName()
            every { aiModelRegistry.getDefault() } returns model

            agentService.getDefaultAgentName() shouldBe "my-agent"

            verify(exactly = 0) { chatClientProvider.getChatClient(any<String>()) }
            verify(exactly = 0) { toolRegistryService.resolveToolsForNamespace(any()) }
        }

        // -------------------------------------------------------------------------
        // resolveAgentName
        // -------------------------------------------------------------------------

        "resolveAgentName returns the canonical name on exact match without instantiating any agent" {
            val model = modelWithDistinctNameAndModelName()
            every { aiModelRegistry.findByName("my-agent") } returns model

            agentService.resolveAgentName("my-agent") shouldBe "my-agent"

            verify(exactly = 0) { chatClientProvider.getChatClient(any<String>()) }
            verify(exactly = 0) { toolRegistryService.resolveToolsForNamespace(any()) }
        }

        "resolveAgentName falls back to contains-match and returns canonical name without instantiating any agent" {
            val model = modelWithDistinctNameAndModelName()
            every { aiModelRegistry.findByName("agent") } returns null
            every { aiModelRegistry.getAll() } returns listOf(model)

            agentService.resolveAgentName("agent") shouldBe "my-agent"

            verify(exactly = 0) { chatClientProvider.getChatClient(any<String>()) }
            verify(exactly = 0) { toolRegistryService.resolveToolsForNamespace(any()) }
        }

        "resolveAgentName returns null when no model matches" {
            every { aiModelRegistry.findByName("unknown") } returns null
            every { aiModelRegistry.getAll() } returns emptyList()

            agentService.resolveAgentName("unknown") shouldBe null
        }
    }
}
