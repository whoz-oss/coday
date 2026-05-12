package io.whozoss.agentos.agent

import io.kotest.assertions.throwables.shouldThrow
import io.kotest.core.spec.style.StringSpec
import io.kotest.matchers.shouldBe
import io.kotest.matchers.string.shouldContain
import io.kotest.matchers.string.shouldNotContain
import io.mockk.every
import io.mockk.mockk
import io.mockk.verify
import io.whozoss.agentos.agentConfig.AgentConfig
import io.whozoss.agentos.agentConfig.AgentConfigService
import io.whozoss.agentos.agentConfig.AgentConfigServiceImpl
import io.whozoss.agentos.aiModel.AiModelService
import io.whozoss.agentos.aiProvider.AiProviderService
import io.whozoss.agentos.chat.ChatClientProvider
import io.whozoss.agentos.integrationConfig.IntegrationConfig
import io.whozoss.agentos.integrationConfig.IntegrationConfigService
import io.whozoss.agentos.namespace.Namespace
import io.whozoss.agentos.namespace.NamespaceService
import io.whozoss.agentos.reconciliation.ConfigMergeService
import io.whozoss.agentos.sdk.aiProvider.AiApiType
import io.whozoss.agentos.sdk.aiProvider.AiModel
import io.whozoss.agentos.sdk.aiProvider.AiProvider
import io.whozoss.agentos.sdk.entity.EntityMetadata
import io.whozoss.agentos.tool.ToolRegistryService
import io.whozoss.agentos.user.User
import io.whozoss.agentos.user.UserService
import org.springframework.ai.chat.client.ChatClient
import java.util.UUID

@Suppress("UNCHECKED_CAST")
class AgentServiceImplUnitSpec : StringSpec() {
    private val chatClientProvider: ChatClientProvider = mockk()
    private val toolRegistryService: ToolRegistryService = mockk()
    private val aiModelService: AiModelService = mockk()
    private val aiProviderService: AiProviderService = mockk()
    private val namespaceService: NamespaceService = mockk()
    private val integrationConfigService: IntegrationConfigService = mockk(relaxed = true)
    private val userService: UserService = mockk(relaxed = true)
    private val aiModelReconciliationService: ConfigMergeService<AiModel> =
        mockk(relaxed = true)
    private val aiProviderReconciliationService: ConfigMergeService<AiProvider> =
        mockk(relaxed = true)
    private val agentConfigService: AgentConfigService = mockk()
    private val agentService =
        AgentServiceImpl(
            chatClientProvider,
            toolRegistryService,
            aiModelService,
            aiProviderService,
            namespaceService,
            integrationConfigService,
            userService,
            aiModelReconciliationService,
            aiProviderReconciliationService,
            agentConfigService,
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
        apiModelName = apiName,
        alias = alias,
        priority = priority,
        temperature = temperature,
        maxTokens = maxTokens,
    )

    private fun agentConfig(
        name: String = "my-agent",
        instructions: String? = null,
        modelName: String? = "sonnet",
    ) = AgentConfig(
        metadata = EntityMetadata(id = UUID.randomUUID()),
        namespaceId = namespaceId,
        name = name,
        instructions = instructions,
        modelName = modelName,
    )

    init {
        every { toolRegistryService.resolveToolsForNamespace(any(), any()) } returns emptyList()
        every { toolRegistryService.resolveToolsForRun(any(), any(), any(), any()) } returns emptyList()
        every { namespaceService.findById(namespaceId) } returns namespace
        every { integrationConfigService.findByParent(any()) } returns emptyList()
        every { userService.findById(any()) } returns null
        // reconciliation services are relaxed mocks — return the base entity unchanged (passthrough) by default

        // -------------------------------------------------------------------------
        // findAgentByName — AgentConfig-first resolution
        // -------------------------------------------------------------------------

        "findAgentByName resolves from AgentConfig when one exists with matching name" {
            val config = agentConfig(name = "my-agent", instructions = "Be helpful.", modelName = "sonnet")
            val model = modelConfig(alias = "sonnet")
            val provider = providerConfig()
            val chatClient = mockk<ChatClient>(relaxed = true)

            every { agentConfigService.findByName(namespaceId, "my-agent") } returns config
            every { aiModelService.findAiModel(namespaceId, "sonnet") } returns model
            every { aiProviderService.getById(aiProviderId) } returns provider
            every { chatClientProvider.getChatClient(model, provider) } returns chatClient

            val agent = agentService.findAgentByName("my-agent", context)

            agent.name shouldBe "my-agent"
        }

        "findAgentByName uses AgentConfig instructions as base of system prompt" {
            val config = agentConfig(name = "my-agent", instructions = "Always respond in French.", modelName = "sonnet")
            val model = modelConfig(alias = "sonnet")
            val provider = providerConfig()
            val chatClient = mockk<ChatClient>(relaxed = true)

            every { agentConfigService.findByName(namespaceId, "my-agent") } returns config
            every { aiModelService.findAiModel(namespaceId, "sonnet") } returns model
            every { aiProviderService.getById(aiProviderId) } returns provider
            every { chatClientProvider.getChatClient(model, provider) } returns chatClient

            val agent = agentService.findAgentByName("my-agent", context) as AgentSimple

            agent.instructions shouldContain "Always respond in French."
        }

        "findAgentByName resolves model via AgentConfig.modelName" {
            val config = agentConfig(name = "my-agent", modelName = "opus")
            val model = modelConfig(apiName = "claude-opus-4-5", alias = "opus")
            val provider = providerConfig()
            val chatClient = mockk<ChatClient>(relaxed = true)

            every { agentConfigService.findByName(namespaceId, "my-agent") } returns config
            every { aiModelService.findAiModel(namespaceId, "opus") } returns model
            every { aiProviderService.getById(aiProviderId) } returns provider
            every { chatClientProvider.getChatClient(model, provider) } returns chatClient

            agentService.findAgentByName("my-agent", context)

            verify(exactly = 1) { aiModelService.findAiModel(namespaceId, "opus") }
            verify(exactly = 1) { chatClientProvider.getChatClient(model, provider) }
        }

        "findAgentByName falls back to namespace default model when AgentConfig has no modelName" {
            val config = agentConfig(name = "my-agent", modelName = null)
            val defaultModel = modelConfig(alias = "default")
            val provider = providerConfig()
            val chatClient = mockk<ChatClient>(relaxed = true)

            every { agentConfigService.findByName(namespaceId, "my-agent") } returns config
            every { aiModelService.findAiModel(namespaceId) } returns defaultModel
            every { aiProviderService.getById(aiProviderId) } returns provider
            every { chatClientProvider.getChatClient(defaultModel, provider) } returns chatClient

            val agent = agentService.findAgentByName("my-agent", context)

            agent.name shouldBe "my-agent"
            verify(exactly = 1) { aiModelService.findAiModel(namespaceId) }
        }

        "findAgentByName applies user overlays on default-model fallback when userId is set" {
            // Regression guard: both the named-model and the default-model branches must
            // route through `applyOverlaysToModel`. If a refactor ever bypasses overlays
            // on the default-model branch (e.g. by skipping reconciliation when modelName
            // is null), the chat client would be built from base instead of overlaid
            // model/provider and the strict mock below would not match.
            val userId = UUID.randomUUID()
            val contextWithUser = AgentExecutionContext(namespaceId = namespaceId, caseId = caseId, userId = userId)
            val config = agentConfig(name = "my-agent", modelName = null)
            val defaultModel = modelConfig(alias = "default")
            val overlaidModel = defaultModel.copy(metadata = EntityMetadata(id = UUID.randomUUID()), priority = 99)
            val baseProvider = providerConfig()
            val overlaidProvider = baseProvider.copy(metadata = EntityMetadata(id = UUID.randomUUID()), apiKey = "user-key")
            val chatClient = mockk<ChatClient>(relaxed = true)

            every { agentConfigService.findByName(namespaceId, "my-agent") } returns config
            every { aiModelService.findAiModel(namespaceId) } returns defaultModel
            every { aiModelReconciliationService.resolve(namespaceId, userId, "default") } returns overlaidModel
            every { aiProviderService.getById(aiProviderId) } returns baseProvider
            every { aiProviderReconciliationService.resolve(namespaceId, userId, "anthropic-prod") } returns overlaidProvider
            every { chatClientProvider.getChatClient(overlaidModel, overlaidProvider) } returns chatClient

            agentService.findAgentByName("my-agent", contextWithUser)

            verify(exactly = 1) { aiModelService.findAiModel(namespaceId) }
            verify(exactly = 1) { aiModelReconciliationService.resolve(namespaceId, userId, "default") }
            verify(exactly = 1) { aiProviderReconciliationService.resolve(namespaceId, userId, "anthropic-prod") }
            verify(exactly = 1) { chatClientProvider.getChatClient(overlaidModel, overlaidProvider) }
        }

        "findAgentByName uses NS aiModel + user-overlaid aiProvider when only the provider has a user override" {
            // Asymmetric overlay scenario (review item 6, user-question driven): NS aiModel
            // `sonnet` is linked to NS aiProvider `anthropic-prod`; the user has only
            // overridden the provider. The agent must receive (NS model, user-overlaid
            // provider) — i.e. the model carries its NS identity but the provider's apiKey
            // and metadata come from the user-level overlay.
            //
            // The strict mock on `getChatClient(baseModel, overlaidProvider)` is the regression
            // guard: a refactor that re-looks up the provider by id (instead of re-resolving by
            // name after the model resolves) would silently lose the overlay.
            val userId = UUID.randomUUID()
            val contextWithUser = AgentExecutionContext(namespaceId = namespaceId, caseId = caseId, userId = userId)
            val config = agentConfig(name = "my-agent", modelName = "sonnet")
            val baseModel = modelConfig(alias = "sonnet")
            val baseProvider = providerConfig(apiKey = "ns-key")
            val overlaidProvider = baseProvider.copy(metadata = EntityMetadata(id = UUID.randomUUID()), apiKey = "user-key")
            val chatClient = mockk<ChatClient>(relaxed = true)

            every { agentConfigService.findByName(namespaceId, "my-agent") } returns config
            every { aiModelService.findAiModel(namespaceId, "sonnet") } returns baseModel
            // Model has no user override → reconciliation passes-through the NS model.
            every { aiModelReconciliationService.resolve(namespaceId, userId, "sonnet") } returns baseModel
            every { aiProviderService.getById(aiProviderId) } returns baseProvider
            // Provider has a user×ns override → reconciliation merges the overlay.
            every { aiProviderReconciliationService.resolve(namespaceId, userId, "anthropic-prod") } returns overlaidProvider
            every { chatClientProvider.getChatClient(baseModel, overlaidProvider) } returns chatClient

            agentService.findAgentByName("my-agent", contextWithUser)

            verify(exactly = 1) { aiModelReconciliationService.resolve(namespaceId, userId, "sonnet") }
            verify(exactly = 1) { aiProviderReconciliationService.resolve(namespaceId, userId, "anthropic-prod") }
            verify(exactly = 1) { chatClientProvider.getChatClient(baseModel, overlaidProvider) }
        }

        "findAgentByName uses user-overlaid aiModel + NS aiProvider when only the model has a user override" {
            // Symmetric companion of the previous test: only the model is user-overlaid.
            // The provider link must follow the *resolved* model — `applyOverlaysToModel`
            // calls `aiProviderService.getById(resolvedModel.aiProviderId)` so a user-overlaid
            // model that retains the same `aiProviderId` (typical case) lands on the NS
            // provider; reconciliation then passes that NS provider through unchanged.
            val userId = UUID.randomUUID()
            val contextWithUser = AgentExecutionContext(namespaceId = namespaceId, caseId = caseId, userId = userId)
            val config = agentConfig(name = "my-agent", modelName = "sonnet")
            val baseModel = modelConfig(alias = "sonnet")
            val overlaidModel = baseModel.copy(metadata = EntityMetadata(id = UUID.randomUUID()), maxTokens = 4096)
            val baseProvider = providerConfig(apiKey = "ns-key")
            val chatClient = mockk<ChatClient>(relaxed = true)

            every { agentConfigService.findByName(namespaceId, "my-agent") } returns config
            every { aiModelService.findAiModel(namespaceId, "sonnet") } returns baseModel
            every { aiModelReconciliationService.resolve(namespaceId, userId, "sonnet") } returns overlaidModel
            // The overlaid model retains the NS aiProviderId → provider lookup hits the NS row.
            every { aiProviderService.getById(aiProviderId) } returns baseProvider
            // No user override on the provider name → reconciliation passes-through.
            every { aiProviderReconciliationService.resolve(namespaceId, userId, "anthropic-prod") } returns baseProvider
            every { chatClientProvider.getChatClient(overlaidModel, baseProvider) } returns chatClient

            agentService.findAgentByName("my-agent", contextWithUser)

            verify(exactly = 1) { aiModelReconciliationService.resolve(namespaceId, userId, "sonnet") }
            verify(exactly = 1) { aiProviderReconciliationService.resolve(namespaceId, userId, "anthropic-prod") }
            verify(exactly = 1) { chatClientProvider.getChatClient(overlaidModel, baseProvider) }
        }

        "findAgentByName resolution is case-insensitive for AgentConfig names" {
            val config = agentConfig(name = "My-Agent", modelName = "sonnet")
            val model = modelConfig(alias = "sonnet")
            val provider = providerConfig()
            val chatClient = mockk<ChatClient>(relaxed = true)

            every { agentConfigService.findByName(namespaceId, "my-agent") } returns config
            every { aiModelService.findAiModel(namespaceId, "sonnet") } returns model
            every { aiProviderService.getById(aiProviderId) } returns provider
            every { chatClientProvider.getChatClient(model, provider) } returns chatClient

            val agent = agentService.findAgentByName("my-agent", context)

            agent.name shouldBe "My-Agent"
        }

        "findAgentByName throws when no AgentConfig matches in the namespace" {
            every { agentConfigService.findByName(namespaceId, "unknown") } returns null

            shouldThrow<IllegalArgumentException> {
                agentService.findAgentByName("unknown", context)
            }
        }

        // -------------------------------------------------------------------------
        // Namespace context injection into instructions
        // -------------------------------------------------------------------------

        "findAgentByName appends namespace name and description to instructions" {
            val config = agentConfig(name = "my-agent", modelName = "sonnet")
            val model = modelConfig(alias = "sonnet")
            val provider = providerConfig()
            val chatClient = mockk<ChatClient>(relaxed = true)

            every { agentConfigService.findByName(namespaceId, "my-agent") } returns config
            every { aiModelService.findAiModel(namespaceId, "sonnet") } returns model
            every { aiProviderService.getById(aiProviderId) } returns provider
            every { chatClientProvider.getChatClient(model, provider) } returns chatClient

            val agent = agentService.findAgentByName("my-agent", context) as AgentSimple

            agent.instructions shouldContain namespace.name
            agent.instructions shouldContain "Engineering namespace for backend services"
        }

        "findAgentByName includes namespace name but not null when namespace has no description" {
            val namespaceWithoutDescription =
                Namespace(
                    metadata = EntityMetadata(id = namespaceId),
                    name = "engineering",
                    description = null,
                )
            every { namespaceService.findById(namespaceId) } returns namespaceWithoutDescription

            val config = agentConfig(name = "my-agent", modelName = "sonnet")
            val model = modelConfig(alias = "sonnet")
            val provider = providerConfig()
            val chatClient = mockk<ChatClient>(relaxed = true)
            every { agentConfigService.findByName(namespaceId, "my-agent") } returns config
            every { aiModelService.findAiModel(namespaceId, "sonnet") } returns model
            every { aiProviderService.getById(aiProviderId) } returns provider
            every { chatClientProvider.getChatClient(model, provider) } returns chatClient

            val agent = agentService.findAgentByName("my-agent", context) as AgentSimple

            agent.instructions shouldContain "engineering"
            agent.instructions shouldNotContain "null"

            // restore default stub
            every { namespaceService.findById(namespaceId) } returns namespace
        }

        // -------------------------------------------------------------------------
        // Integration descriptions block injection into instructions
        // -------------------------------------------------------------------------

        "findAgentByName omits integrations block when agentConfig has no integrations (null)" {
            val configs =
                listOf(
                    IntegrationConfig(
                        metadata = EntityMetadata(id = UUID.randomUUID()),
                        namespaceId = namespaceId,
                        name = "JIRA_PROD",
                        integrationType = "JIRA",
                        description = "Production Jira workspace",
                    ),
                )
            every { integrationConfigService.findByParent(namespaceId) } returns configs
            // agentConfig has no integrations field (null)
            val config = agentConfig(name = "my-agent", modelName = "sonnet")
            val model = modelConfig(alias = "sonnet")
            val provider = providerConfig()
            val chatClient = mockk<ChatClient>(relaxed = true)
            every { agentConfigService.findByName(namespaceId, "my-agent") } returns config
            every { aiModelService.findAiModel(namespaceId, "sonnet") } returns model
            every { aiProviderService.getById(aiProviderId) } returns provider
            every { chatClientProvider.getChatClient(model, provider) } returns chatClient

            val agent = agentService.findAgentByName("my-agent", context) as AgentSimple

            agent.instructions shouldNotContain "## Integrations"
            agent.instructions shouldNotContain "JIRA_PROD"
        }

        "findAgentByName appends integrations block only for configs listed in agentConfig.integrations" {
            val localIntegrationService = mockk<IntegrationConfigService>()
            val localService =
                AgentServiceImpl(
                    chatClientProvider,
                    toolRegistryService,
                    aiModelService,
                    aiProviderService,
                    namespaceService,
                    localIntegrationService,
                    userService,
                    aiModelReconciliationService,
                    aiProviderReconciliationService,
                    agentConfigService,
                )
            val configs =
                listOf(
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
            every { localIntegrationService.findByParent(any()) } returns configs
            // Agent only declares JIRA_PROD
            val config = agentConfig(name = "my-agent", modelName = "sonnet")
                .copy(integrations = mapOf("JIRA_PROD" to null))
            val model = modelConfig(alias = "sonnet")
            val provider = providerConfig()
            val chatClient = mockk<ChatClient>(relaxed = true)
            every { agentConfigService.findByName(namespaceId, "my-agent") } returns config
            every { aiModelService.findAiModel(namespaceId, "sonnet") } returns model
            every { aiProviderService.getById(aiProviderId) } returns provider
            every { chatClientProvider.getChatClient(model, provider) } returns chatClient

            val agent = localService.findAgentByName("my-agent", context) as AgentSimple

            agent.instructions shouldContain "## Integrations"
            agent.instructions shouldContain "JIRA_PROD: Production Jira workspace for the engineering team"
            agent.instructions shouldNotContain "SLACK_DEV"
        }

        "findAgentByName omits integrations block when listed configs have no description" {
            val configs =
                listOf(
                    IntegrationConfig(
                        metadata = EntityMetadata(id = UUID.randomUUID()),
                        namespaceId = namespaceId,
                        name = "JIRA_PROD",
                        integrationType = "JIRA",
                        description = null,
                    ),
                )
            every { integrationConfigService.findByParent(namespaceId) } returns configs
            val config = agentConfig(name = "my-agent", modelName = "sonnet")
                .copy(integrations = mapOf("JIRA_PROD" to null))
            val model = modelConfig(alias = "sonnet")
            val provider = providerConfig()
            val chatClient = mockk<ChatClient>(relaxed = true)
            every { agentConfigService.findByName(namespaceId, "my-agent") } returns config
            every { aiModelService.findAiModel(namespaceId, "sonnet") } returns model
            every { aiProviderService.getById(aiProviderId) } returns provider
            every { chatClientProvider.getChatClient(model, provider) } returns chatClient

            val agent = agentService.findAgentByName("my-agent", context) as AgentSimple

            agent.instructions shouldNotContain "## Integrations"
        }

        "findAgentByName resolves namespace by namespaceId from context" {
            val config = agentConfig(name = "my-agent", modelName = "sonnet")
            val model = modelConfig(alias = "sonnet")
            val provider = providerConfig()
            val chatClient = mockk<ChatClient>(relaxed = true)
            every { agentConfigService.findByName(namespaceId, "my-agent") } returns config
            every { aiModelService.findAiModel(namespaceId, "sonnet") } returns model
            every { aiProviderService.getById(aiProviderId) } returns provider
            every { chatClientProvider.getChatClient(model, provider) } returns chatClient

            agentService.findAgentByName("my-agent", context)

            verify(exactly = 1) { namespaceService.findById(namespaceId) }
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
            val config = agentConfig(name = "my-agent", modelName = "sonnet")
            val model = modelConfig(alias = "sonnet")
            val provider = providerConfig()
            val chatClient = mockk<ChatClient>(relaxed = true)

            every { agentConfigService.findByName(namespaceId, "my-agent") } returns config
            every { aiModelService.findAiModel(namespaceId, "sonnet") } returns model
            every { aiModelReconciliationService.resolve(namespaceId, userId, "sonnet") } returns model
            every { aiProviderService.getById(aiProviderId) } returns provider
            every { aiProviderReconciliationService.resolve(namespaceId, userId, "anthropic-prod") } returns provider
            every { chatClientProvider.getChatClient(model, provider) } returns chatClient
            every { userService.findById(userId) } returns user

            val agent = agentService.findAgentByName("my-agent", contextWithUser) as AgentSimple

            agent.instructions shouldContain user.email
            agent.instructions shouldContain "Alice"
            agent.instructions shouldContain "Smith"
            agent.instructions shouldContain "Backend engineer passionate about distributed systems."
            agent.instructions shouldContain userId.toString()
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
            val config = agentConfig(name = "my-agent", modelName = "sonnet")
            val model = modelConfig(alias = "sonnet")
            val provider = providerConfig()
            val chatClient = mockk<ChatClient>(relaxed = true)

            every { agentConfigService.findByName(namespaceId, "my-agent") } returns config
            every { aiModelService.findAiModel(namespaceId, "sonnet") } returns model
            every { aiModelReconciliationService.resolve(namespaceId, userId, "sonnet") } returns model
            every { aiProviderService.getById(aiProviderId) } returns provider
            every { aiProviderReconciliationService.resolve(namespaceId, userId, "anthropic-prod") } returns provider
            every { chatClientProvider.getChatClient(model, provider) } returns chatClient
            every { userService.findById(userId) } returns user

            val agent = agentService.findAgentByName("my-agent", contextWithUser) as AgentSimple

            agent.instructions shouldContain user.email
            agent.instructions shouldNotContain "firstname"
            agent.instructions shouldNotContain "lastname"
            agent.instructions shouldNotContain "bio"
        }

        "findAgentByName skips user block when userId is null" {
            val config = agentConfig(name = "my-agent", modelName = "sonnet")
            val model = modelConfig(alias = "sonnet")
            val provider = providerConfig()
            val chatClient = mockk<ChatClient>(relaxed = true)

            every { agentConfigService.findByName(namespaceId, "my-agent") } returns config
            every { aiModelService.findAiModel(namespaceId, "sonnet") } returns model
            every { aiProviderService.getById(aiProviderId) } returns provider
            every { chatClientProvider.getChatClient(model, provider) } returns chatClient

            val agent = agentService.findAgentByName("my-agent", context) as AgentSimple

            agent.instructions shouldNotContain "## User"
            verify(exactly = 0) { userService.findById(any()) }
        }

        // -------------------------------------------------------------------------
        // Tool filtering via agentConfig.integrations
        // -------------------------------------------------------------------------

        "findAgentByName passes null integrations to ToolRegistryService when agentConfig has no integrations" {
            val config = agentConfig(name = "my-agent", modelName = "sonnet")
            val model = modelConfig(alias = "sonnet")
            val provider = providerConfig()
            val chatClient = mockk<ChatClient>(relaxed = true)

            every { agentConfigService.findByName(namespaceId, "my-agent") } returns config
            every { aiModelService.findAiModel(namespaceId, "sonnet") } returns model
            every { aiProviderService.getById(aiProviderId) } returns provider
            every { chatClientProvider.getChatClient(model, provider) } returns chatClient

            agentService.findAgentByName("my-agent", context)

            verify(exactly = 1) { toolRegistryService.resolveToolsForNamespace(namespaceId, null) }
        }

        "findAgentByName passes integrations map to ToolRegistryService when agentConfig has integrations" {
            val integrations = mapOf("FILES" to null, "JIRA_PROD" to listOf("GetIssue"))
            val config = agentConfig(name = "my-agent", modelName = "sonnet").copy(integrations = integrations)
            val model = modelConfig(alias = "sonnet")
            val provider = providerConfig()
            val chatClient = mockk<ChatClient>(relaxed = true)

            every { agentConfigService.findByName(namespaceId, "my-agent") } returns config
            every { aiModelService.findAiModel(namespaceId, "sonnet") } returns model
            every { aiProviderService.getById(aiProviderId) } returns provider
            every { chatClientProvider.getChatClient(model, provider) } returns chatClient

            agentService.findAgentByName("my-agent", context)

            verify(exactly = 1) { toolRegistryService.resolveToolsForNamespace(namespaceId, integrations) }
        }

        // -------------------------------------------------------------------------
        // getDefaultAgentName
        // -------------------------------------------------------------------------

        "getDefaultAgentName returns AgentConfig name when one exists" {
            val config = agentConfig(name = "my-agent")
            every { agentConfigService.findDefault(namespaceId) } returns config

            agentService.getDefaultAgentName(namespaceId) shouldBe "my-agent"

            verify(exactly = 0) { aiModelService.findAiModel(any()) }
            verify(exactly = 0) { chatClientProvider.getChatClient(any(), any()) }
        }

        "getDefaultAgentName returns built-in fallback name when no AgentConfig is persisted" {
            every { agentConfigService.findDefault(namespaceId) } returns AgentConfigServiceImpl.DEFAULT_AGENT_CONFIG

            agentService.getDefaultAgentName(namespaceId) shouldBe "Default Agent"

            verify(exactly = 0) { aiModelService.findAiModel(any()) }
            verify(exactly = 0) { chatClientProvider.getChatClient(any(), any()) }
        }

        // -------------------------------------------------------------------------
        // resolveAgentName
        // -------------------------------------------------------------------------

        "resolveAgentName returns AgentConfig name when one matches" {
            val config = agentConfig(name = "my-agent")
            every { agentConfigService.findByName(namespaceId, "my-agent") } returns config

            agentService.resolveAgentName("my-agent", namespaceId) shouldBe "my-agent"

            verify(exactly = 0) { aiModelService.findAiModel(any(), any()) }
        }

        "resolveAgentName returns null when no AgentConfig matches" {
            every { agentConfigService.findByName(namespaceId, "unknown") } returns null

            agentService.resolveAgentName("unknown", namespaceId) shouldBe null
        }
    }
}
