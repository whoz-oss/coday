package io.whozoss.agentos.agent

import io.kotest.assertions.throwables.shouldThrow
import io.kotest.core.spec.style.StringSpec
import io.kotest.matchers.nulls.shouldBeNull
import io.kotest.matchers.shouldBe
import io.kotest.matchers.string.shouldContain
import io.kotest.matchers.string.shouldNotContain
import io.mockk.every
import io.mockk.mockk
import io.mockk.verify
import io.whozoss.agentos.aiModel.AiModelService
import io.whozoss.agentos.aiProvider.AiProviderService
import io.whozoss.agentos.chat.ChatClientProvider
import io.whozoss.agentos.namespace.Namespace
import io.whozoss.agentos.namespace.NamespaceService
import io.whozoss.agentos.sdk.aiProvider.AiApiType
import io.whozoss.agentos.sdk.aiProvider.AiModel
import io.whozoss.agentos.sdk.aiProvider.AiProvider
import io.whozoss.agentos.sdk.entity.EntityMetadata
import io.whozoss.agentos.tool.ToolRegistryService
import io.whozoss.agentos.user.User
import io.whozoss.agentos.user.UserService
import org.springframework.ai.chat.client.ChatClient
import java.util.UUID

class AgentServiceImplSpec : StringSpec() {
    private val chatClientProvider: ChatClientProvider = mockk()
    private val toolRegistryService: ToolRegistryService = mockk()
    private val aiModelService: AiModelService = mockk()
    private val aiProviderService: AiProviderService = mockk()
    private val namespaceService: NamespaceService = mockk()
    private val userService: UserService = mockk(relaxed = true)
    private val agentService =
        AgentServiceImpl(
            chatClientProvider,
            toolRegistryService,
            aiModelService,
            aiProviderService,
            namespaceService,
            userService,
        )

    private val namespaceId: UUID = UUID.randomUUID()
    private val aiProviderId: UUID = UUID.randomUUID()
    private val caseId: UUID = UUID.randomUUID()
    private val context = AgentExecutionContext(namespaceId = namespaceId, caseId = caseId)
    private val namespace =
        Namespace(
            metadata = EntityMetadata(id = namespaceId),
            name = "engineering",
            description = "Engineering namespace for backend services",
        )

    private fun providerConfig(apiKey: String? = "sk-ant-test") =
        AiProvider(
            metadata = EntityMetadata(id = aiProviderId),
            namespaceId = namespaceId,
            name = "anthropic-prod",
            apiType = AiApiType.Anthropic,
            baseUrl = "https://api.anthropic.com",
            apiKey = apiKey,
        )

    private fun modelConfig(
        apiName: String = "claude-sonnet-4-5",
        alias: String? = "sonnet",
        priority: Int = 0,
        temperature: Double? = null,
        maxTokens: Int? = null,
    ) = AiModel(
        metadata = EntityMetadata(id = UUID.randomUUID()),
        aiProviderId = aiProviderId,
        namespaceId = namespaceId,
        apiName = apiName,
        alias = alias,
        priority = priority,
        temperature = temperature,
        maxTokens = maxTokens,
    )

    init {
        every { toolRegistryService.resolveToolsForNamespace(any()) } returns emptyList()
        every { namespaceService.findById(namespaceId) } returns namespace

        // -------------------------------------------------------------------------
        // findAgentByName — alias resolution
        // -------------------------------------------------------------------------

        "findAgentByName resolves by alias and creates an agent" {
            val model = modelConfig(alias = "sonnet")
            val provider = providerConfig()
            val chatClient = mockk<ChatClient>(relaxed = true)

            every { aiModelService.findAiModel(namespaceId, "sonnet") } returns model
            every { aiProviderService.getById(aiProviderId) } returns provider
            every { chatClientProvider.getChatClient(model, provider) } returns chatClient

            val agent = agentService.findAgentByName("sonnet", context)

            agent.name shouldBe "sonnet"
            verify(exactly = 1) { chatClientProvider.getChatClient(model, provider) }
        }

        "findAgentByName falls back to apiName when alias does not match" {
            val model = modelConfig(apiName = "claude-sonnet-4-5", alias = null)
            val provider = providerConfig()
            val chatClient = mockk<ChatClient>(relaxed = true)

            every { aiModelService.findAiModel(namespaceId, "claude-sonnet-4-5") } returns model
            every { aiProviderService.getById(aiProviderId) } returns provider
            every { chatClientProvider.getChatClient(model, provider) } returns chatClient

            val agent = agentService.findAgentByName("claude-sonnet-4-5", context)

            agent.name shouldBe "claude-sonnet-4-5"
        }

        "findAgentByName resolves to higher-priority config when two configs share the same alias" {
            // Resolution logic (alias priority, tie-breaking) is tested exhaustively in
            // LlmModelConfigServiceImplSpec. Here we only verify that findAgentByName
            // delegates to findModelConfig and uses the returned config.
            val highPriority = modelConfig(apiName = "claude-sonnet-4-5", alias = "sonnet", priority = 10)
            val provider = providerConfig()
            val chatClient = mockk<ChatClient>(relaxed = true)

            every { aiModelService.findAiModel(namespaceId, "sonnet") } returns highPriority
            every { aiProviderService.getById(aiProviderId) } returns provider
            every { chatClientProvider.getChatClient(highPriority, provider) } returns chatClient

            agentService.findAgentByName("sonnet", context)

            verify(exactly = 1) { chatClientProvider.getChatClient(highPriority, provider) }
        }

        "findAgentByName prefers alias over apiName when both could match" {
            val withAlias = modelConfig(apiName = "claude-sonnet-4-5", alias = "sonnet")
            val provider = providerConfig()
            val chatClient = mockk<ChatClient>(relaxed = true)

            every { aiModelService.findAiModel(namespaceId, "sonnet") } returns withAlias
            every { aiProviderService.getById(aiProviderId) } returns provider
            every { chatClientProvider.getChatClient(withAlias, provider) } returns chatClient

            val agent = agentService.findAgentByName("sonnet", context)

            agent.name shouldBe "sonnet"
            verify(exactly = 1) { chatClientProvider.getChatClient(withAlias, provider) }
        }

        "findAgentByName matching is case-insensitive" {
            val model = modelConfig(alias = "Sonnet")
            val provider = providerConfig()
            val chatClient = mockk<ChatClient>(relaxed = true)

            every { aiModelService.findAiModel(namespaceId, "sonnet") } returns model
            every { aiProviderService.getById(aiProviderId) } returns provider
            every { chatClientProvider.getChatClient(model, provider) } returns chatClient

            agentService.findAgentByName("sonnet", context).name shouldBe "Sonnet"
        }

        "findAgentByName throws when no LlmModelConfig matches in the namespace" {
            every { aiModelService.findAiModel(namespaceId, "sonnet") } returns null

            shouldThrow<IllegalArgumentException> {
                agentService.findAgentByName("sonnet", context)
            }
        }

        // -------------------------------------------------------------------------
        // Namespace context injection into instructions
        // -------------------------------------------------------------------------

        "findAgentByName appends namespace name and description to instructions" {
            val model = modelConfig()
            val provider = providerConfig()
            val chatClient = mockk<ChatClient>(relaxed = true)

            every { aiModelService.findAiModel(namespaceId, "sonnet") } returns model
            every { aiProviderService.getById(aiProviderId) } returns provider
            every { chatClientProvider.getChatClient(model, provider) } returns chatClient

            val agent = agentService.findAgentByName("sonnet", context) as AgentSimple

            agent.instructions!! shouldContain namespace.name
            agent.instructions!! shouldContain namespace.description!!
        }

        "findAgentByName includes namespace name but not null when namespace has no description" {
            val namespaceWithoutDescription =
                Namespace(
                    metadata = EntityMetadata(id = namespaceId),
                    name = "engineering",
                    description = null,
                )
            every { namespaceService.findById(namespaceId) } returns namespaceWithoutDescription

            val model = modelConfig()
            val provider = providerConfig()
            val chatClient = mockk<ChatClient>(relaxed = true)
            every { aiModelService.findAiModel(namespaceId, "sonnet") } returns model
            every { aiProviderService.getById(aiProviderId) } returns provider
            every { chatClientProvider.getChatClient(model, provider) } returns chatClient

            val agent = agentService.findAgentByName("sonnet", context) as AgentSimple

            agent.instructions!! shouldContain "engineering"
            agent.instructions!! shouldNotContain "null"

            // restore default stub
            every { namespaceService.findById(namespaceId) } returns namespace
        }

        // -------------------------------------------------------------------------
        // User context injection into instructions
        // -------------------------------------------------------------------------

        "findAgentByName appends user fields to instructions when userId resolves a known user" {
            val userId = UUID.randomUUID()
            val user =
                User(
                    metadata = EntityMetadata(id = userId),
                    externalId = "ext-123",
                    email = "alice@example.com",
                    firstname = "Alice",
                    lastname = "Smith",
                    bio = "Backend engineer passionate about distributed systems.",
                )
            val contextWithUser = AgentExecutionContext(namespaceId = namespaceId, caseId = caseId, userId = userId)
            val model = modelConfig()
            val provider = providerConfig()
            val chatClient = mockk<ChatClient>(relaxed = true)

            every { aiModelService.findAiModel(namespaceId, "sonnet") } returns model
            every { aiProviderService.getById(aiProviderId) } returns provider
            every { chatClientProvider.getChatClient(model, provider) } returns chatClient
            every { userService.findById(userId) } returns user

            val agent = agentService.findAgentByName("sonnet", contextWithUser) as AgentSimple

            agent.instructions!! shouldContain user.email
            agent.instructions!! shouldContain user.firstname!!
            agent.instructions!! shouldContain user.lastname!!
            agent.instructions!! shouldContain user.bio!!
            agent.instructions!! shouldContain userId.toString()
        }

        "findAgentByName omits optional user fields that are blank" {
            val userId = UUID.randomUUID()
            val user =
                User(
                    metadata = EntityMetadata(id = userId),
                    externalId = "ext-456",
                    email = "bob@example.com",
                    firstname = null,
                    lastname = null,
                    bio = null,
                )
            val contextWithUser = AgentExecutionContext(namespaceId = namespaceId, caseId = caseId, userId = userId)
            val model = modelConfig()
            val provider = providerConfig()
            val chatClient = mockk<ChatClient>(relaxed = true)

            every { aiModelService.findAiModel(namespaceId, "sonnet") } returns model
            every { aiProviderService.getById(aiProviderId) } returns provider
            every { chatClientProvider.getChatClient(model, provider) } returns chatClient
            every { userService.findById(userId) } returns user

            val agent = agentService.findAgentByName("sonnet", contextWithUser) as AgentSimple

            agent.instructions!! shouldContain user.email
            agent.instructions!! shouldNotContain "firstname"
            agent.instructions!! shouldNotContain "lastname"
            agent.instructions!! shouldNotContain "bio"
        }

        "findAgentByName skips user block when userId is null" {
            val model = modelConfig()
            val provider = providerConfig()
            val chatClient = mockk<ChatClient>(relaxed = true)

            every { aiModelService.findAiModel(namespaceId, "sonnet") } returns model
            every { aiProviderService.getById(aiProviderId) } returns provider
            every { chatClientProvider.getChatClient(model, provider) } returns chatClient

            val agent = agentService.findAgentByName("sonnet", context) as AgentSimple

            agent.instructions!! shouldNotContain "## User"
            verify(exactly = 0) { userService.findById(any()) }
        }

        // -------------------------------------------------------------------------
        // getDefaultAgentName
        // -------------------------------------------------------------------------

        "getDefaultAgentName delegates to llmModelConfigService.findModelConfig and returns alias" {
            val defaultModel = modelConfig(apiName = "claude-sonnet-4-5", alias = "default")
            every { aiModelService.findAiModel(namespaceId) } returns defaultModel

            agentService.getDefaultAgentName(namespaceId) shouldBe "default"

            verify(exactly = 0) { chatClientProvider.getChatClient(any(), any()) }
            verify(exactly = 0) { toolRegistryService.resolveToolsForNamespace(any()) }
        }

        "getDefaultAgentName returns null when findModelConfig returns null" {
            every { aiModelService.findAiModel(namespaceId) } returns null

            agentService.getDefaultAgentName(namespaceId).shouldBeNull()
        }

        // -------------------------------------------------------------------------
        // resolveAgentName — delegates to findModelConfig
        // -------------------------------------------------------------------------

        "resolveAgentName returns alias when findModelConfig resolves by alias" {
            val model = modelConfig(alias = "sonnet")
            every { aiModelService.findAiModel(namespaceId, "sonnet") } returns model

            agentService.resolveAgentName("sonnet", namespaceId) shouldBe "sonnet"

            verify(exactly = 0) { chatClientProvider.getChatClient(any(), any()) }
        }

        "resolveAgentName returns apiName when findModelConfig resolves by apiName" {
            val model = modelConfig(apiName = "claude-sonnet-4-5", alias = null)
            every { aiModelService.findAiModel(namespaceId, "claude-sonnet-4-5") } returns model

            agentService.resolveAgentName("claude-sonnet-4-5", namespaceId) shouldBe "claude-sonnet-4-5"
        }

        "resolveAgentName returns null when findModelConfig returns null" {
            every { aiModelService.findAiModel(namespaceId, "unknown") } returns null

            agentService.resolveAgentName("unknown", namespaceId).shouldBeNull()
        }
    }
}
