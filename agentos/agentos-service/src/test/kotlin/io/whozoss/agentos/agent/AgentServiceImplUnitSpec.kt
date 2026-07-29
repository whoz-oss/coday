package io.whozoss.agentos.agent

import com.fasterxml.jackson.databind.ObjectMapper
import io.kotest.assertions.throwables.shouldThrow
import io.kotest.core.spec.style.StringSpec
import io.kotest.matchers.collections.shouldBeEmpty
import io.kotest.matchers.nulls.shouldNotBeNull
import io.kotest.matchers.shouldBe
import io.kotest.matchers.string.shouldContain
import io.kotest.matchers.string.shouldNotContain
import io.mockk.coEvery
import io.mockk.coVerify
import io.mockk.every
import io.mockk.mockk
import io.mockk.verify
import io.whozoss.agentos.agentConfig.AgentConfig
import io.whozoss.agentos.agentConfig.AgentConfigService
import io.whozoss.agentos.agentConfig.AgentDocumentResolver
import io.whozoss.agentos.aiModel.AiModelService
import io.whozoss.agentos.aiProvider.AiProviderService
import io.whozoss.agentos.auth.AuthServiceFactory
import io.whozoss.agentos.caseEvent.CaseEventService
import io.whozoss.agentos.chat.ChatClientProvider
import io.whozoss.agentos.exchange.ExchangeCapabilityService
import io.whozoss.agentos.exchange.ExchangeGrant
import io.whozoss.agentos.exchange.ExchangeStorageConfigProperties
import io.whozoss.agentos.exchange.ExchangeStorageService
import io.whozoss.agentos.exchange.ExchangeToolGrantService
import io.whozoss.agentos.exchange.ExchangeToolsConfigProperties
import io.whozoss.agentos.integrationConfig.IntegrationConfig
import io.whozoss.agentos.integrationConfig.IntegrationConfigService
import io.whozoss.agentos.metrics.ToolMetricsService
import io.whozoss.agentos.namespace.Namespace
import io.whozoss.agentos.namespace.NamespaceService
import io.whozoss.agentos.permissions.EntityType
import io.whozoss.agentos.sdk.aiProvider.AiApiType
import io.whozoss.agentos.sdk.aiProvider.AiModel
import io.whozoss.agentos.sdk.aiProvider.AiProvider
import io.whozoss.agentos.sdk.entity.EntityMetadata
import io.whozoss.agentos.sdk.tool.StandardTool
import io.whozoss.agentos.sdk.tool.ToolPlugin
import io.whozoss.agentos.tool.ToolRegistryService
import io.whozoss.agentos.tool.ToolResolverService
import io.whozoss.agentos.user.User
import io.whozoss.agentos.user.UserService
import io.whozoss.agentos.util.IdCompressorService
import org.springframework.ai.chat.client.ChatClient
import java.nio.file.Files
import java.nio.file.Path
import java.time.Instant
import java.util.UUID

class AgentServiceImplUnitSpec : StringSpec() {
    private val chatClientProvider: ChatClientProvider = mockk()
    private val toolResolverService: ToolResolverService = mockk()
    private val aiModelService: AiModelService = mockk()
    private val aiProviderService: AiProviderService = mockk()
    private val namespaceService: NamespaceService = mockk()
    private val integrationConfigService: IntegrationConfigService = mockk(relaxed = true)
    private val userService: UserService = mockk(relaxed = true)
    private val agentConfigService: AgentConfigService = mockk()
    private val intentionGenerator: AgentIntentionGenerator = mockk(relaxed = true)
    private val confirmationManager: ConfirmationManager = mockk(relaxed = true)
    private val testObjectMapper = ObjectMapper()
    private val toolRegistryService: ToolRegistryService = mockk(relaxed = true)
    private val toolMetricsService: ToolMetricsService = mockk(relaxed = true)
    private val caseEventService: CaseEventService = mockk(relaxed = true)
    private val authServiceFactory: AuthServiceFactory = mockk(relaxed = true)
    private val agentDocumentResolver: AgentDocumentResolver = mockk(relaxed = true)
    private val exchangeStorageService: ExchangeStorageService = mockk(relaxed = true)
    private val exchangeCapabilityService: ExchangeCapabilityService = mockk(relaxed = true)

    // Strict on purpose: a relaxed mock would return a non-null ExchangeGrant and silently grant the
    // exchange in every unrelated test. The defaults stubbed in init deny both scopes.
    private val exchangeToolGrantService: ExchangeToolGrantService = mockk()
    private val agentService =
        AgentServiceImpl(
            chatClientProvider = chatClientProvider,
            toolResolverService = toolResolverService,
            aiModelService = aiModelService,
            aiProviderService = aiProviderService,
            namespaceService = namespaceService,
            integrationConfigService = integrationConfigService,
            userService = userService,
            agentConfigService = agentConfigService,
            intentionGenerator = intentionGenerator,
            confirmationManager = confirmationManager,
            objectMapper = testObjectMapper,
            toolRegistryService = toolRegistryService,
            toolMetricsService = toolMetricsService,
            caseEventService = caseEventService,
            authServiceFactory = authServiceFactory,
            exchangeStorageService = exchangeStorageService,
            exchangeCapabilityService = exchangeCapabilityService,
            exchangeToolGrantService = exchangeToolGrantService,
            agentDocumentResolver = agentDocumentResolver,
            idCompressorService = IdCompressorService(),
            agentConfigProperties = AgentConfigProperties(),
        )

    private val namespaceId: UUID = UUID.randomUUID()
    private val aiProviderId: UUID = UUID.randomUUID()
    private val caseId: UUID = UUID.randomUUID()
    private val caseCreatedAt: Instant = Instant.parse("2025-12-15T10:30:00Z")
    private val context = AgentExecutionContext(namespaceId = namespaceId, caseId = caseId, caseCreatedAt = caseCreatedAt)
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
        every { toolResolverService.resolveToolsForRun(agentIntegrations = any(), context = any(), allIntegrationConfigs = any(), credentialProviderFactory = any()) } returns
            emptyList()
        // dedupToolsByName is the shared collision-reconciler; identity is fine (no collisions in these tests).
        every { toolResolverService.dedupToolsByName(any()) } answers { firstArg() }
        // Both exchange scopes are denied unless a test says otherwise, mirroring the shipped defaults.
        every { exchangeToolGrantService.resolveCaseGrant(any()) } returns null
        every { exchangeToolGrantService.resolveNamespaceGrant(any()) } returns null
        every { exchangeToolGrantService.grantTools(any(), any(), any(), any(), any()) } returns emptyList()

        every { namespaceService.findById(namespaceId) } returns namespace
        every { integrationConfigService.findByParent(any()) } returns emptyList()
        every { integrationConfigService.findEffective(any(), null) } returns emptyList()
        every { userService.findById(any()) } returns null
        // No plugin contributes a namespace description by default
        every { toolRegistryService.findPlugin(any()) } returns null
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
            every { chatClientProvider.getChatClient(model, provider, any()) } returns chatClient

            val agent = agentService.findAgentByName("my-agent", context)

            agent.name shouldBe "my-agent"
        }

        // -------------------------------------------------------------------------
        // File-exchange tool gating — what only AgentServiceImpl knows: the run's case id and the
        // invoking user's Namespace READ/WRITE rights. The enablement rules and the plugin config
        // node belong to ExchangeToolGrantService and are covered by its own spec.
        // -------------------------------------------------------------------------

        "case exchange is granted at the case root with read/write when a live case is present" {
            val caseRootPath = Path.of("/tmp/case-exchange-test")
            every { exchangeToolGrantService.resolveCaseGrant(any()) } returns ExchangeGrant(allowedTools = null)
            every { exchangeStorageService.caseRoot(namespaceId, caseId, any()) } returns caseRootPath

            val config = agentConfig(name = "case-agent", modelName = "sonnet").copy(integrations = mapOf("CASE_FILE_EXCHANGE" to null))
            every { agentConfigService.findByName(namespaceId, "case-agent") } returns config
            every { aiModelService.findAiModel(namespaceId, "sonnet") } returns modelConfig(alias = "sonnet")
            every { aiProviderService.getById(aiProviderId) } returns providerConfig()
            every { chatClientProvider.getChatClient(any(), any(), any()) } returns mockk<ChatClient>(relaxed = true)

            agentService.findAgentByName("case-agent", context)

            // The agent's own integrations map must reach the grant service verbatim: a regression
            // passing e.g. null instead would let a default-on instance re-grant tools to an agent
            // that opted out.
            verify(exactly = 1) { exchangeToolGrantService.resolveCaseGrant(mapOf("CASE_FILE_EXCHANGE" to null)) }
            verify(exactly = 1) {
                exchangeToolGrantService.grantTools(
                    root = caseRootPath,
                    readOnly = false,
                    configName = "case-exchange",
                    allowedTools = null,
                    toolContext = any(),
                )
            }
        }

        "the per-tool allowlist carried by the grant is forwarded to the file-plugin grant" {
            val caseRootPath = Path.of("/tmp/case-exchange-allowlist-test")
            every { exchangeToolGrantService.resolveCaseGrant(any()) } returns ExchangeGrant(allowedTools = listOf("readFile"))
            every { exchangeStorageService.caseRoot(namespaceId, caseId, any()) } returns caseRootPath

            val config =
                agentConfig(name = "case-agent-filtered", modelName = "sonnet")
                    .copy(integrations = mapOf("CASE_FILE_EXCHANGE" to listOf("readFile")))
            every { agentConfigService.findByName(namespaceId, "case-agent-filtered") } returns config
            every { aiModelService.findAiModel(namespaceId, "sonnet") } returns modelConfig(alias = "sonnet")
            every { aiProviderService.getById(aiProviderId) } returns providerConfig()
            every { chatClientProvider.getChatClient(any(), any(), any()) } returns mockk<ChatClient>(relaxed = true)

            agentService.findAgentByName("case-agent-filtered", context)

            verify(exactly = 1) {
                exchangeToolGrantService.resolveCaseGrant(mapOf("CASE_FILE_EXCHANGE" to listOf("readFile")))
            }
            verify(exactly = 1) {
                exchangeToolGrantService.grantTools(
                    root = caseRootPath,
                    readOnly = false,
                    configName = "case-exchange",
                    allowedTools = listOf("readFile"),
                    toolContext = any(),
                )
            }
        }

        "namespace exchange is denied without an identified user, and case is fail-closed without a live case id" {
            every { exchangeToolGrantService.resolveCaseGrant(any()) } returns ExchangeGrant(allowedTools = null)
            every { exchangeToolGrantService.resolveNamespaceGrant(any()) } returns ExchangeGrant(allowedTools = null)

            val config =
                agentConfig(name = "ns-agent", modelName = "sonnet").copy(
                    integrations =
                        mapOf(
                            "CASE_FILE_EXCHANGE" to null,
                            "NAMESPACE_FILE_EXCHANGE" to null,
                        ),
                )
            every { agentConfigService.findById(config.metadata.id) } returns config
            every { aiModelService.findAiModel(namespaceId, "sonnet") } returns modelConfig(alias = "sonnet")
            every { aiProviderService.getById(aiProviderId) } returns providerConfig()

            agentService.resolveDefinition(config.metadata.id, namespaceId, userId = null)

            // The grant service still receives the agent's exact integrations map...
            verify(exactly = 1) {
                exchangeToolGrantService.resolveNamespaceGrant(
                    mapOf("CASE_FILE_EXCHANGE" to null, "NAMESPACE_FILE_EXCHANGE" to null),
                )
            }
            // ...but Namespace READ cannot be established without an identified user, so the
            // namespace grant is denied fail-closed before any permission query; and with no live
            // case id the case scope is not granted either. Nothing may be built.
            verify(exactly = 0) { exchangeCapabilityService.canRead(any(), any(), any()) }
            verify(exactly = 0) { exchangeToolGrantService.grantTools(any(), any(), any(), any(), any()) }
        }

        "namespace exchange is granted read-only when the user holds Namespace READ but not WRITE" {
            val nsRootPath = Path.of("/tmp/ns-exchange-ro-test")
            val readerId = UUID.randomUUID()
            every { exchangeToolGrantService.resolveNamespaceGrant(any()) } returns ExchangeGrant(allowedTools = null)
            every { exchangeStorageService.namespaceRoot(namespaceId) } returns nsRootPath
            every {
                exchangeCapabilityService.canRead(readerId.toString(), EntityType.NAMESPACE, namespaceId.toString())
            } returns true
            every {
                exchangeCapabilityService.canWrite(readerId.toString(), EntityType.NAMESPACE, namespaceId.toString())
            } returns false

            val config =
                agentConfig(name = "ns-reader", modelName = "sonnet")
                    .copy(integrations = mapOf("NAMESPACE_FILE_EXCHANGE" to null))
            every { agentConfigService.findById(config.metadata.id) } returns config
            every { aiModelService.findAiModel(namespaceId, "sonnet") } returns modelConfig(alias = "sonnet")
            every { aiProviderService.getById(aiProviderId) } returns providerConfig()
            every { aiProviderService.resolveProvider(any(), any(), any()) } returns providerConfig()

            agentService.resolveDefinition(config.metadata.id, namespaceId, userId = readerId)

            verify(exactly = 1) {
                exchangeToolGrantService.grantTools(
                    root = nsRootPath,
                    readOnly = true,
                    configName = "namespace-exchange",
                    allowedTools = null,
                    toolContext = any(),
                )
            }
        }

        "namespace exchange is denied when the user lacks Namespace READ, before any write check" {
            // The hole this closes: a user holding a direct Case edge without namespace membership
            // must not read the whole namespace exchange through an agent.
            val strangerId = UUID.randomUUID()
            every { exchangeToolGrantService.resolveNamespaceGrant(any()) } returns ExchangeGrant(allowedTools = null)
            every {
                exchangeCapabilityService.canRead(strangerId.toString(), EntityType.NAMESPACE, namespaceId.toString())
            } returns false

            val config =
                agentConfig(name = "ns-stranger", modelName = "sonnet")
                    .copy(integrations = mapOf("NAMESPACE_FILE_EXCHANGE" to null))
            every { agentConfigService.findById(config.metadata.id) } returns config
            every { aiModelService.findAiModel(namespaceId, "sonnet") } returns modelConfig(alias = "sonnet")
            every { aiProviderService.getById(aiProviderId) } returns providerConfig()
            every { aiProviderService.resolveProvider(any(), any(), any()) } returns providerConfig()

            agentService.resolveDefinition(config.metadata.id, namespaceId, userId = strangerId)

            verify(exactly = 0) { exchangeCapabilityService.canWrite(any(), any(), any()) }
            verify(exactly = 0) { exchangeToolGrantService.grantTools(any(), any(), any(), any(), any()) }
        }

        "namespace exchange is read/write when the invoking user holds Namespace WRITE" {
            val nsRootPath = Path.of("/tmp/ns-exchange-rw-test")
            val writerId = UUID.randomUUID()
            every { exchangeToolGrantService.resolveNamespaceGrant(any()) } returns ExchangeGrant(allowedTools = null)
            every { exchangeStorageService.namespaceRoot(namespaceId) } returns nsRootPath
            every {
                exchangeCapabilityService.canRead(writerId.toString(), EntityType.NAMESPACE, namespaceId.toString())
            } returns true
            every {
                exchangeCapabilityService.canWrite(writerId.toString(), EntityType.NAMESPACE, namespaceId.toString())
            } returns true

            val config =
                agentConfig(name = "ns-writer", modelName = "sonnet")
                    .copy(integrations = mapOf("NAMESPACE_FILE_EXCHANGE" to null))
            every { agentConfigService.findById(config.metadata.id) } returns config
            every { aiModelService.findAiModel(namespaceId, "sonnet") } returns modelConfig(alias = "sonnet")
            every { aiProviderService.getById(aiProviderId) } returns providerConfig()
            // A non-null userId drives the provider-reconciliation overlay; keep it returning a real provider.
            every { aiProviderService.resolveProvider(any(), any(), any()) } returns providerConfig()

            agentService.resolveDefinition(config.metadata.id, namespaceId, userId = writerId)

            verify(exactly = 1) {
                exchangeToolGrantService.resolveNamespaceGrant(mapOf("NAMESPACE_FILE_EXCHANGE" to null))
            }
            verify(exactly = 1) {
                exchangeToolGrantService.grantTools(
                    root = nsRootPath,
                    readOnly = false,
                    configName = "namespace-exchange",
                    allowedTools = null,
                    toolContext = any(),
                )
            }
        }

        "namespace permissions are not queried when the namespace grant is denied" {
            // Locks the decision-before-permission order: canRead/canWrite are Neo4j lookups, and a
            // scope nobody was granted must not pay for them. Regressing to computing the rights up
            // front would make this fail.
            val config = agentConfig(name = "no-exchange-agent", modelName = "sonnet")
            every { agentConfigService.findById(config.metadata.id) } returns config
            every { aiModelService.findAiModel(namespaceId, "sonnet") } returns modelConfig(alias = "sonnet")
            every { aiProviderService.getById(aiProviderId) } returns providerConfig()
            every { aiProviderService.resolveProvider(any(), any(), any()) } returns providerConfig()

            agentService.resolveDefinition(config.metadata.id, namespaceId, userId = UUID.randomUUID())

            verify(exactly = 0) { exchangeCapabilityService.canRead(any(), any(), any()) }
            verify(exactly = 0) { exchangeCapabilityService.canWrite(any(), any(), any()) }
        }

        "with the real grant service, no integrations key gets case tools on a default-on instance and [] gets none" {
            // Composes the REAL ExchangeToolGrantService so the AgentServiceImpl -> grant-service
            // seam is exercised end to end: a regression passing anything but the agent's own
            // integrations map would surface here as the opted-out agent regaining tools.
            val realGrantService =
                ExchangeToolGrantService(
                    properties = ExchangeToolsConfigProperties(caseEnabledByDefault = true),
                    storageProperties = ExchangeStorageConfigProperties(),
                    toolRegistryService = toolRegistryService,
                    toolResolverService = toolResolverService,
                    objectMapper = testObjectMapper,
                )
            val composedService =
                AgentServiceImpl(
                    chatClientProvider = chatClientProvider,
                    toolResolverService = toolResolverService,
                    aiModelService = aiModelService,
                    aiProviderService = aiProviderService,
                    namespaceService = namespaceService,
                    integrationConfigService = integrationConfigService,
                    userService = userService,
                    agentConfigService = agentConfigService,
                    intentionGenerator = intentionGenerator,
                    confirmationManager = confirmationManager,
                    objectMapper = testObjectMapper,
                    toolRegistryService = toolRegistryService,
                    toolMetricsService = toolMetricsService,
                    caseEventService = caseEventService,
                    exchangeStorageService = exchangeStorageService,
                    exchangeCapabilityService = exchangeCapabilityService,
                    exchangeToolGrantService = realGrantService,
                    agentDocumentResolver = agentDocumentResolver,
                    idCompressorService = IdCompressorService(),
                    agentConfigProperties = AgentConfigProperties(),
                )
            val caseTool = mockk<StandardTool<*>>()
            every { caseTool.name } returns "case-exchange__readFile"
            every { caseTool.description } returns "read a file from the case exchange"
            val filePlugin = mockk<ToolPlugin>()
            every { filePlugin.provideTools(any(), "case-exchange", any()) } returns listOf(caseTool)
            every { toolRegistryService.findPlugin("FILE_ACCESS") } returns filePlugin
            every { toolResolverService.isToolAllowed(any(), any(), any()) } returns true
            // Capture what reaches the shared de-dup: the definitive combined tool set.
            val combinedTools = mutableListOf<List<StandardTool<*>>>()
            every { toolResolverService.dedupToolsByName(capture(combinedTools)) } answers { firstArg() }
            every { aiModelService.findAiModel(namespaceId, "sonnet") } returns modelConfig(alias = "sonnet")
            every { aiProviderService.getById(aiProviderId) } returns providerConfig()
            every { chatClientProvider.getChatClient(any(), any(), any()) } returns mockk<ChatClient>(relaxed = true)

            // No integrations key at all: the platform default grants the case exchange.
            val silentRoot = Files.createTempDirectory("agent-exchange-compose").resolve("case-root")
            every { exchangeStorageService.caseRoot(namespaceId, caseId, any()) } returns silentRoot
            val silentConfig = agentConfig(name = "silent-agent", modelName = "sonnet")
            every { agentConfigService.findByName(namespaceId, "silent-agent") } returns silentConfig

            composedService.findAgentByName("silent-agent", context)

            combinedTools.last().map { it.name } shouldBe listOf("case-exchange__readFile")
            Files.exists(silentRoot) shouldBe true

            // An explicit [] opts out even on the default-on instance: no tools, no scope directory.
            val optedOutRoot = Files.createTempDirectory("agent-exchange-optout").resolve("case-root")
            every { exchangeStorageService.caseRoot(namespaceId, caseId, any()) } returns optedOutRoot
            val optedOutConfig =
                agentConfig(name = "opted-out-agent", modelName = "sonnet")
                    .copy(integrations = mapOf("CASE_FILE_EXCHANGE" to emptyList()))
            every { agentConfigService.findByName(namespaceId, "opted-out-agent") } returns optedOutConfig

            composedService.findAgentByName("opted-out-agent", context)

            combinedTools.last().shouldBeEmpty()
            Files.exists(optedOutRoot) shouldBe false
        }

        // WZ-31596: when advancedExecution=true, AgentAdvancedContext must receive the
        // injected ConfirmationManager + ObjectMapper, otherwise tools opting into the
        // confirmation flow throw IllegalStateException at runtime.
        "findAgentByName with advancedExecution=true wires confirmationManager into AgentAdvancedContext" {
            val config = agentConfig(name = "advanced-agent", modelName = "sonnet").copy(advancedExecution = true)
            val model = modelConfig(alias = "sonnet")
            val provider = providerConfig()
            val chatClient = mockk<ChatClient>(relaxed = true)

            every { agentConfigService.findByName(namespaceId, "advanced-agent") } returns config
            every { aiModelService.findAiModel(namespaceId, "sonnet") } returns model
            every { aiProviderService.getById(aiProviderId) } returns provider
            every { chatClientProvider.getChatClient(model, provider, any()) } returns chatClient

            val agent = agentService.findAgentByName("advanced-agent", context) as AgentAdvanced

            // Use reflection to read the private 'context' field — the wiring is the AC.
            val contextField = AgentAdvanced::class.java.getDeclaredField("context").apply { isAccessible = true }
            val advancedCtx = contextField.get(agent) as AgentAdvancedContext
            advancedCtx.confirmationManager.shouldNotBeNull()
            advancedCtx.confirmationManager shouldBe confirmationManager
        }

        "findAgentByName uses AgentConfig instructions as base of system prompt" {
            val config = agentConfig(name = "my-agent", instructions = "Always respond in French.", modelName = "sonnet")
            val model = modelConfig(alias = "sonnet")
            val provider = providerConfig()
            val chatClient = mockk<ChatClient>(relaxed = true)

            every { agentConfigService.findByName(namespaceId, "my-agent") } returns config
            every { aiModelService.findAiModel(namespaceId, "sonnet") } returns model
            every { aiProviderService.getById(aiProviderId) } returns provider
            every { chatClientProvider.getChatClient(model, provider, any()) } returns chatClient

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
            every { chatClientProvider.getChatClient(model, provider, any()) } returns chatClient

            agentService.findAgentByName("my-agent", context)

            verify(exactly = 1) { aiModelService.findAiModel(namespaceId, "opus") }
            verify(exactly = 1) { chatClientProvider.getChatClient(model, provider, any()) }
        }

        "findAgentByName falls back to namespace default model when AgentConfig has no modelName" {
            val config = agentConfig(name = "my-agent", modelName = null)
            val defaultModel = modelConfig(alias = "default")
            val provider = providerConfig()
            val chatClient = mockk<ChatClient>(relaxed = true)

            every { agentConfigService.findByName(namespaceId, "my-agent") } returns config
            every { aiModelService.findAiModel(namespaceId) } returns defaultModel
            every { aiProviderService.getById(aiProviderId) } returns provider
            every { chatClientProvider.getChatClient(defaultModel, provider, any()) } returns chatClient

            val agent = agentService.findAgentByName("my-agent", context)

            agent.name shouldBe "my-agent"
            verify(exactly = 1) { aiModelService.findAiModel(namespaceId) }
        }

        "findAgentByName resolution is case-insensitive for AgentConfig names" {
            val config = agentConfig(name = "My-Agent", modelName = "sonnet")
            val model = modelConfig(alias = "sonnet")
            val provider = providerConfig()
            val chatClient = mockk<ChatClient>(relaxed = true)

            every { agentConfigService.findByName(namespaceId, "my-agent") } returns config
            every { aiModelService.findAiModel(namespaceId, "sonnet") } returns model
            every { aiProviderService.getById(aiProviderId) } returns provider
            every { chatClientProvider.getChatClient(model, provider, any()) } returns chatClient

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
        // Platform-level model and provider resolution
        //
        // A platform-level AiModel (namespaceId=null) backed by a platform-level
        // AiProvider (namespaceId=null, userId=null) must be usable from any namespace.
        // AgentServiceImpl.applyOverlaysToModel loads the provider via getById; when
        // userId is absent in the context it bypasses reconciliation and uses the
        // provider directly — so a platform provider must work unchanged.
        // -------------------------------------------------------------------------

        "findAgentByName resolves agent when model and provider are platform-level (namespaceId=null)" {
            val platformProviderId = UUID.randomUUID()
            val platformModel =
                AiModel(
                    metadata = EntityMetadata(id = UUID.randomUUID()),
                    aiProviderId = platformProviderId,
                    namespaceId = null, // platform-level: inherited from platform AiProvider
                    apiModelName = "claude-sonnet-4-5",
                    alias = "default",
                    priority = 0,
                )
            val platformProvider =
                AiProvider(
                    metadata = EntityMetadata(id = platformProviderId),
                    namespaceId = null, // platform-level
                    userId = null,
                    name = "anthropic-platform",
                    apiType = AiApiType.Anthropic,
                    apiKey = "sk-platform-key",
                )
            val config = agentConfig(name = "my-agent", modelName = "default")
            val chatClient = mockk<ChatClient>(relaxed = true)

            every { agentConfigService.findByName(namespaceId, "my-agent") } returns config
            every { aiModelService.findAiModel(namespaceId, "default") } returns platformModel
            every { aiProviderService.getById(platformProviderId) } returns platformProvider
            every { chatClientProvider.getChatClient(platformModel, platformProvider, any()) } returns chatClient

            val agent = agentService.findAgentByName("my-agent", context)

            agent.name shouldBe "my-agent"
            verify(exactly = 1) { chatClientProvider.getChatClient(platformModel, platformProvider, any()) }
        }

        "findAgentByName uses resolveProvider when userId is set" {
            // When a userId is present, applyOverlaysToModel delegates to
            // aiProviderService.resolveProvider which fetches all layers in one query.
            val userId = UUID.randomUUID()
            val platformProviderId = UUID.randomUUID()
            val platformModel =
                AiModel(
                    metadata = EntityMetadata(id = UUID.randomUUID()),
                    aiProviderId = platformProviderId,
                    namespaceId = null,
                    apiModelName = "claude-sonnet-4-5",
                    alias = "default",
                    priority = 0,
                )
            val platformProvider =
                AiProvider(
                    metadata = EntityMetadata(id = platformProviderId),
                    namespaceId = null,
                    userId = null,
                    name = "anthropic-platform",
                    apiType = AiApiType.Anthropic,
                    apiKey = "sk-platform-key",
                )
            val contextWithUser = AgentExecutionContext(namespaceId = namespaceId, caseId = caseId, userId = userId)
            val config = agentConfig(name = "my-agent", modelName = "default")
            val chatClient = mockk<ChatClient>(relaxed = true)

            // userId != null => findAgentByName takes the findDeployedByNamespaceIdAndUserIdAndName path
            every { agentConfigService.findDeployedByNamespaceIdAndUserIdAndName(namespaceId, userId, "my-agent") } returns listOf(config)
            every { aiModelService.findAiModel(namespaceId, "default") } returns platformModel
            every { aiProviderService.getById(platformProviderId) } returns platformProvider
            every { aiProviderService.resolveProvider(namespaceId, userId, "anthropic-platform") } returns platformProvider
            every { chatClientProvider.getChatClient(platformModel, platformProvider, any()) } returns chatClient
            every { userService.findById(userId) } returns null

            val agent = agentService.findAgentByName("my-agent", contextWithUser)

            agent.name shouldBe "my-agent"
            verify(exactly = 1) { aiProviderService.resolveProvider(namespaceId, userId, "anthropic-platform") }
            verify(exactly = 1) { chatClientProvider.getChatClient(platformModel, platformProvider, any()) }
        }

        // -------------------------------------------------------------------------
        // Namespace context injection into instructions
        // -------------------------------------------------------------------------

        "findAgentByName includes namespace name and description in system prompt" {
            val config = agentConfig(name = "my-agent", modelName = "sonnet")
            val model = modelConfig(alias = "sonnet")
            val provider = providerConfig()
            val chatClient = mockk<ChatClient>(relaxed = true)

            every { agentConfigService.findByName(namespaceId, "my-agent") } returns config
            every { aiModelService.findAiModel(namespaceId, "sonnet") } returns model
            every { aiProviderService.getById(aiProviderId) } returns provider
            every { chatClientProvider.getChatClient(model, provider, any()) } returns chatClient

            val agent = agentService.findAgentByName("my-agent", context) as AgentSimple

            agent.systemPrompt shouldContain namespace.name
            agent.systemPrompt shouldContain "Engineering namespace for backend services"
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
            every { chatClientProvider.getChatClient(model, provider, any()) } returns chatClient

            val agent = agentService.findAgentByName("my-agent", context) as AgentSimple

            agent.systemPrompt shouldContain "engineering"
            agent.systemPrompt shouldNotContain "null"

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
            every { integrationConfigService.findEffective(namespaceId, null) } returns configs
            // agentConfig has no integrations field (null)
            val config = agentConfig(name = "my-agent", modelName = "sonnet")
            val model = modelConfig(alias = "sonnet")
            val provider = providerConfig()
            val chatClient = mockk<ChatClient>(relaxed = true)
            every { agentConfigService.findByName(namespaceId, "my-agent") } returns config
            every { aiModelService.findAiModel(namespaceId, "sonnet") } returns model
            every { aiProviderService.getById(aiProviderId) } returns provider
            every { chatClientProvider.getChatClient(model, provider, any()) } returns chatClient

            val agent = agentService.findAgentByName("my-agent", context) as AgentSimple

            (agent.instructions ?: "") shouldNotContain "## Integrations"
            (agent.instructions ?: "") shouldNotContain "JIRA_PROD"
        }

        "findAgentByName appends integrations block only for configs listed in agentConfig.integrations" {
            val localIntegrationService = mockk<IntegrationConfigService>()
            val localService =
                AgentServiceImpl(
                    chatClientProvider = chatClientProvider,
                    toolResolverService = toolResolverService,
                    aiModelService = aiModelService,
                    aiProviderService = aiProviderService,
                    namespaceService = namespaceService,
                    integrationConfigService = localIntegrationService,
                    userService = userService,
                    agentConfigService = agentConfigService,
                    intentionGenerator = intentionGenerator,
                    confirmationManager = confirmationManager,
                    objectMapper = testObjectMapper,
                    toolRegistryService = toolRegistryService,
                    toolMetricsService = toolMetricsService,
                    caseEventService = caseEventService,
                    authServiceFactory = authServiceFactory,
                    exchangeStorageService = exchangeStorageService,
                    exchangeCapabilityService = exchangeCapabilityService,
                    exchangeToolGrantService = exchangeToolGrantService,
                    agentDocumentResolver = agentDocumentResolver,
                    idCompressorService = IdCompressorService(),
                    agentConfigProperties = AgentConfigProperties(),
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
            every { localIntegrationService.findEffective(any(), null) } returns configs
            // Agent only declares JIRA_PROD
            val config =
                agentConfig(name = "my-agent", modelName = "sonnet")
                    .copy(integrations = mapOf("JIRA_PROD" to null))
            val model = modelConfig(alias = "sonnet")
            val provider = providerConfig()
            val chatClient = mockk<ChatClient>(relaxed = true)
            every { agentConfigService.findByName(namespaceId, "my-agent") } returns config
            every { aiModelService.findAiModel(namespaceId, "sonnet") } returns model
            every { aiProviderService.getById(aiProviderId) } returns provider
            every { chatClientProvider.getChatClient(model, provider, any()) } returns chatClient

            val agent = localService.findAgentByName("my-agent", context) as AgentSimple

            agent.instructions shouldContain "## Integrations"
            agent.instructions shouldContain "- JIRA_PROD: Production Jira workspace for the engineering team"
            agent.instructions shouldNotContain "SLACK_DEV"
        }

        "findAgentByName appends describeNamespace result from matching integration config to the namespace system prompt" {
            val plugin = mockk<ToolPlugin>()
            val integrationConfig =
                IntegrationConfig(
                    metadata = EntityMetadata(id = UUID.randomUUID()),
                    namespaceId = namespaceId,
                    name = "JIRA_PROD",
                    integrationType = "JIRA",
                    description = null,
                )
            every { integrationConfigService.findEffective(namespaceId, null) } returns listOf(integrationConfig)
            every { toolRegistryService.findPlugin("JIRA") } returns plugin
            coEvery { plugin.describeNamespace(any(), eq("JIRA_PROD"), any()) } returns "Jira workspace: ACME Engineering (42 open issues)"
            val config = agentConfig(name = "my-agent", modelName = "sonnet")
            val model = modelConfig(alias = "sonnet")
            val provider = providerConfig()
            val chatClient = mockk<ChatClient>(relaxed = true)
            every { agentConfigService.findByName(namespaceId, "my-agent") } returns config
            every { aiModelService.findAiModel(namespaceId, "sonnet") } returns model
            every { aiProviderService.getById(aiProviderId) } returns provider
            every { chatClientProvider.getChatClient(model, provider, any()) } returns chatClient

            val agent = agentService.findAgentByName("my-agent", context) as AgentSimple

            agent.systemPrompt shouldContain "Jira workspace: ACME Engineering (42 open issues)"

            // restore default stubs
            every { integrationConfigService.findEffective(any(), null) } returns emptyList()
            every { toolRegistryService.findPlugin(any()) } returns null
        }

        "findAgentByName skips integration config when describeNamespace returns null" {
            val plugin = mockk<ToolPlugin>()
            val integrationConfig =
                IntegrationConfig(
                    metadata = EntityMetadata(id = UUID.randomUUID()),
                    namespaceId = namespaceId,
                    name = "JIRA_PROD",
                    integrationType = "JIRA",
                    description = null,
                )
            every { integrationConfigService.findEffective(namespaceId, null) } returns listOf(integrationConfig)
            every { toolRegistryService.findPlugin("JIRA") } returns plugin
            coEvery { plugin.describeNamespace(any(), any(), any()) } returns null
            val config = agentConfig(name = "my-agent", modelName = "sonnet")
            val model = modelConfig(alias = "sonnet")
            val provider = providerConfig()
            val chatClient = mockk<ChatClient>(relaxed = true)
            every { agentConfigService.findByName(namespaceId, "my-agent") } returns config
            every { aiModelService.findAiModel(namespaceId, "sonnet") } returns model
            every { aiProviderService.getById(aiProviderId) } returns provider
            every { chatClientProvider.getChatClient(model, provider, any()) } returns chatClient

            val agent = agentService.findAgentByName("my-agent", context) as AgentSimple

            agent.systemPrompt shouldContain namespace.name
            agent.systemPrompt shouldNotContain "null"

            // restore default stubs
            every { integrationConfigService.findEffective(any(), null) } returns emptyList()
            every { toolRegistryService.findPlugin(any()) } returns null
        }

        "findAgentByName does not propagate exception from describeNamespace" {
            val plugin = mockk<ToolPlugin>()
            val integrationConfig =
                IntegrationConfig(
                    metadata = EntityMetadata(id = UUID.randomUUID()),
                    namespaceId = namespaceId,
                    name = "JIRA_PROD",
                    integrationType = "JIRA",
                    description = null,
                )
            every { integrationConfigService.findEffective(namespaceId, null) } returns listOf(integrationConfig)
            every { toolRegistryService.findPlugin("JIRA") } returns plugin
            coEvery { plugin.describeNamespace(any(), any(), any()) } throws RuntimeException("remote API unavailable")
            val config = agentConfig(name = "my-agent", modelName = "sonnet")
            val model = modelConfig(alias = "sonnet")
            val provider = providerConfig()
            val chatClient = mockk<ChatClient>(relaxed = true)
            every { agentConfigService.findByName(namespaceId, "my-agent") } returns config
            every { aiModelService.findAiModel(namespaceId, "sonnet") } returns model
            every { aiProviderService.getById(aiProviderId) } returns provider
            every { chatClientProvider.getChatClient(model, provider, any()) } returns chatClient

            // Must not throw — agent instantiation succeeds even when a plugin fails
            val agent = agentService.findAgentByName("my-agent", context) as AgentSimple
            agent.systemPrompt shouldContain namespace.name

            // restore default stubs
            every { integrationConfigService.findEffective(any(), null) } returns emptyList()
            every { toolRegistryService.findPlugin(any()) } returns null
        }

        "findAgentByName calls describeNamespace once per integration config with matching plugin" {
            val jiraPlugin = mockk<ToolPlugin>()
            val slackPlugin = mockk<ToolPlugin>()
            val jiraConfig =
                IntegrationConfig(
                    metadata = EntityMetadata(id = UUID.randomUUID()),
                    namespaceId = namespaceId,
                    name = "JIRA_PROD",
                    integrationType = "JIRA",
                    description = null,
                )
            val slackConfig =
                IntegrationConfig(
                    metadata = EntityMetadata(id = UUID.randomUUID()),
                    namespaceId = namespaceId,
                    name = "SLACK_DEV",
                    integrationType = "SLACK",
                    description = null,
                )
            every { integrationConfigService.findEffective(namespaceId, null) } returns listOf(jiraConfig, slackConfig)
            every { toolRegistryService.findPlugin("JIRA") } returns jiraPlugin
            every { toolRegistryService.findPlugin("SLACK") } returns slackPlugin
            coEvery { jiraPlugin.describeNamespace(any(), eq("JIRA_PROD"), any()) } returns "Jira namespace info"
            coEvery { slackPlugin.describeNamespace(any(), eq("SLACK_DEV"), any()) } returns "Slack namespace info"
            val config = agentConfig(name = "my-agent", modelName = "sonnet")
            val model = modelConfig(alias = "sonnet")
            val provider = providerConfig()
            val chatClient = mockk<ChatClient>(relaxed = true)
            every { agentConfigService.findByName(namespaceId, "my-agent") } returns config
            every { aiModelService.findAiModel(namespaceId, "sonnet") } returns model
            every { aiProviderService.getById(aiProviderId) } returns provider
            every { chatClientProvider.getChatClient(model, provider, any()) } returns chatClient

            val agent = agentService.findAgentByName("my-agent", context) as AgentSimple

            agent.systemPrompt shouldContain "Jira namespace info"
            agent.systemPrompt shouldContain "Slack namespace info"
            coVerify(exactly = 1) { jiraPlugin.describeNamespace(any(), eq("JIRA_PROD"), any()) }
            coVerify(exactly = 1) { slackPlugin.describeNamespace(any(), eq("SLACK_DEV"), any()) }

            // restore default stubs
            every { integrationConfigService.findEffective(any(), null) } returns emptyList()
            every { toolRegistryService.findPlugin(any()) } returns null
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
            every { integrationConfigService.findEffective(namespaceId, null) } returns configs
            val config =
                agentConfig(name = "my-agent", modelName = "sonnet")
                    .copy(integrations = mapOf("JIRA_PROD" to null))
            val model = modelConfig(alias = "sonnet")
            val provider = providerConfig()
            val chatClient = mockk<ChatClient>(relaxed = true)
            every { agentConfigService.findByName(namespaceId, "my-agent") } returns config
            every { aiModelService.findAiModel(namespaceId, "sonnet") } returns model
            every { aiProviderService.getById(aiProviderId) } returns provider
            every { chatClientProvider.getChatClient(model, provider, any()) } returns chatClient

            val agent = agentService.findAgentByName("my-agent", context) as AgentSimple

            (agent.instructions ?: "") shouldNotContain "## Integrations"
        }

        "findAgentByName resolves namespace by namespaceId from context" {
            val config = agentConfig(name = "my-agent", modelName = "sonnet")
            val model = modelConfig(alias = "sonnet")
            val provider = providerConfig()
            val chatClient = mockk<ChatClient>(relaxed = true)
            every { agentConfigService.findByName(namespaceId, "my-agent") } returns config
            every { aiModelService.findAiModel(namespaceId, "sonnet") } returns model
            every { aiProviderService.getById(aiProviderId) } returns provider
            every { chatClientProvider.getChatClient(model, provider, any()) } returns chatClient

            agentService.findAgentByName("my-agent", context)

            verify(exactly = 1) { namespaceService.findById(namespaceId) }
        }

        // -------------------------------------------------------------------------
        // User context injection into instructions
        // -------------------------------------------------------------------------

        // -------------------------------------------------------------------------
        // findAgentByName — platform vs namespace shadowing (userId path)
        // -------------------------------------------------------------------------

        "findAgentByName with userId prefers namespace-scoped agent over platform agent with same name" {
            val userId = UUID.randomUUID()
            val nsAgent = agentConfig(name = "my-agent", modelName = "sonnet") // namespaceId set
            val platformAgent =
                agentConfig(name = "my-agent", modelName = "sonnet")
                    .copy(
                        metadata = EntityMetadata(id = UUID.randomUUID()),
                    ).let { it.copy(metadata = it.metadata) }
            // Simulate platform agent: create a copy with namespaceId = null
            val platformAgentNullNs =
                AgentConfig(
                    metadata = EntityMetadata(id = UUID.randomUUID()),
                    namespaceId = null,
                    name = "my-agent",
                    modelName = "sonnet",
                )
            val contextWithUser = AgentExecutionContext(namespaceId = namespaceId, caseId = caseId, userId = userId)
            val model = modelConfig(alias = "sonnet")
            val provider = providerConfig()
            val chatClient = mockk<ChatClient>(relaxed = true)

            // Both namespace and platform agent returned by the query
            every {
                agentConfigService.findDeployedByNamespaceIdAndUserIdAndName(namespaceId, userId, "my-agent")
            } returns listOf(nsAgent, platformAgentNullNs)
            every { aiModelService.findAiModel(namespaceId, "sonnet") } returns model
            every { aiProviderService.getById(aiProviderId) } returns provider
            every { aiProviderService.resolveProvider(namespaceId, userId, "anthropic-prod") } returns provider
            every { chatClientProvider.getChatClient(model, provider, any()) } returns chatClient
            every { userService.findById(userId) } returns null

            // Must resolve without error, and must pick the namespace-scoped agent
            val agent = agentService.findAgentByName("my-agent", contextWithUser)
            agent.name shouldBe "my-agent"
        }

        "findAgentByName with userId throws when two namespace-level agents match the name" {
            // Two distinct configs sharing the same name at namespace level is an error —
            // resolveWithShadowing enforces uniqueness per level.
            val userId = UUID.randomUUID()
            val ns1 = agentConfig(name = "my-agent", modelName = "sonnet")
            val ns2 = agentConfig(name = "my-agent", modelName = "sonnet") // same name, different id
            val contextWithUser = AgentExecutionContext(namespaceId = namespaceId, caseId = caseId, userId = userId)

            every {
                agentConfigService.findDeployedByNamespaceIdAndUserIdAndName(namespaceId, userId, "my-agent")
            } returns listOf(ns1, ns2)

            io.kotest.assertions.throwables.shouldThrow<IllegalArgumentException> {
                agentService.findAgentByName("my-agent", contextWithUser)
            }
        }

        "findAgentByName with userId does not match agent whose name contains the search term as substring" {
            // Regression test for the contains() bug: searching for "Archay" must not match
            // "Searchay" even though "archay" is a substring of "searchay".
            val userId = UUID.randomUUID()
            val archay = agentConfig(name = "Archay", modelName = "sonnet")
            val searchay = agentConfig(name = "Searchay", modelName = "sonnet")
            val contextWithUser = AgentExecutionContext(namespaceId = namespaceId, caseId = caseId, userId = userId)
            val model = modelConfig(alias = "sonnet")
            val provider = providerConfig()
            val chatClient = mockk<ChatClient>(relaxed = true)

            every {
                agentConfigService.findDeployedByNamespaceIdAndUserIdAndName(namespaceId, userId, "archay")
            } returns listOf(archay, searchay)
            every { aiModelService.findAiModel(namespaceId, "sonnet") } returns model
            every { aiProviderService.getById(aiProviderId) } returns provider
            every { aiProviderService.resolveProvider(namespaceId, userId, "anthropic-prod") } returns provider
            every { chatClientProvider.getChatClient(model, provider, any()) } returns chatClient
            every { userService.findById(userId) } returns null

            val agent = agentService.findAgentByName("Archay", contextWithUser)
            agent.name shouldBe "Archay"
        }

        "findAgentByName with userId exact match is case-insensitive" {
            val userId = UUID.randomUUID()
            val myAgent = agentConfig(name = "MyAgent", modelName = "sonnet")
            val contextWithUser = AgentExecutionContext(namespaceId = namespaceId, caseId = caseId, userId = userId)
            val model = modelConfig(alias = "sonnet")
            val provider = providerConfig()
            val chatClient = mockk<ChatClient>(relaxed = true)

            every {
                agentConfigService.findDeployedByNamespaceIdAndUserIdAndName(namespaceId, userId, "myagent")
            } returns listOf(myAgent)
            every { aiModelService.findAiModel(namespaceId, "sonnet") } returns model
            every { aiProviderService.getById(aiProviderId) } returns provider
            every { aiProviderService.resolveProvider(namespaceId, userId, "anthropic-prod") } returns provider
            every { chatClientProvider.getChatClient(model, provider, any()) } returns chatClient
            every { userService.findById(userId) } returns null

            val agent = agentService.findAgentByName("myagent", contextWithUser)
            agent.name shouldBe "MyAgent"
        }

        "findAgentByName with userId resolves single platform agent when no namespace agent matches" {
            val userId = UUID.randomUUID()
            val platformAgent =
                AgentConfig(
                    metadata = EntityMetadata(id = UUID.randomUUID()),
                    namespaceId = null,
                    name = "platform-agent",
                    modelName = "sonnet",
                )
            val contextWithUser = AgentExecutionContext(namespaceId = namespaceId, caseId = caseId, userId = userId)
            val model = modelConfig(alias = "sonnet")
            val provider = providerConfig()
            val chatClient = mockk<ChatClient>(relaxed = true)

            every {
                agentConfigService.findDeployedByNamespaceIdAndUserIdAndName(namespaceId, userId, "platform-agent")
            } returns listOf(platformAgent)
            every { aiModelService.findAiModel(namespaceId, "sonnet") } returns model
            every { aiProviderService.getById(aiProviderId) } returns provider
            every { aiProviderService.resolveProvider(namespaceId, userId, "anthropic-prod") } returns provider
            every { chatClientProvider.getChatClient(model, provider, any()) } returns chatClient
            every { userService.findById(userId) } returns null

            val agent = agentService.findAgentByName("platform-agent", contextWithUser)
            agent.name shouldBe "platform-agent"
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

            every { agentConfigService.findDeployedByNamespaceIdAndUserIdAndName(namespaceId, userId, "my-agent") } returns listOf(config)
            every { aiModelService.findAiModel(namespaceId, "sonnet") } returns model

            every { aiProviderService.getById(aiProviderId) } returns provider
            every { aiProviderService.resolveProvider(namespaceId, userId, "anthropic-prod") } returns provider
            every { chatClientProvider.getChatClient(model, provider, any()) } returns chatClient
            every { userService.findById(userId) } returns user

            val agent = agentService.findAgentByName("my-agent", contextWithUser) as AgentSimple

            agent.instructions shouldContain user.email
            agent.instructions shouldContain "Alice"
            agent.instructions shouldContain "Smith"
            agent.instructions shouldContain "Backend engineer passionate about distributed systems."
            // Internal AgentOS UUID must NOT appear in the prompt — it carries no conversational
            // meaning and confuses the LLM about who the interlocutor is.
            agent.instructions shouldNotContain userId.toString()
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

            every { agentConfigService.findDeployedByNamespaceIdAndUserIdAndName(namespaceId, userId, "my-agent") } returns listOf(config)
            every { aiModelService.findAiModel(namespaceId, "sonnet") } returns model

            every { aiProviderService.getById(aiProviderId) } returns provider
            every { aiProviderService.resolveProvider(namespaceId, userId, "anthropic-prod") } returns provider
            every { chatClientProvider.getChatClient(model, provider, any()) } returns chatClient
            every { userService.findById(userId) } returns user

            val agent = agentService.findAgentByName("my-agent", contextWithUser) as AgentSimple

            // Email alone is enough to produce a ## User block.
            agent.instructions shouldContain user.email
            agent.instructions shouldNotContain "firstname"
            agent.instructions shouldNotContain "lastname"
            agent.instructions shouldNotContain "bio"
            // Internal UUID must never appear regardless of which fields are populated.
            agent.instructions shouldNotContain userId.toString()
        }

        "findAgentByName skips user block when userId is null" {
            val config = agentConfig(name = "my-agent", modelName = "sonnet")
            val model = modelConfig(alias = "sonnet")
            val provider = providerConfig()
            val chatClient = mockk<ChatClient>(relaxed = true)

            every { agentConfigService.findByName(namespaceId, "my-agent") } returns config
            every { aiModelService.findAiModel(namespaceId, "sonnet") } returns model
            every { aiProviderService.getById(aiProviderId) } returns provider
            every { chatClientProvider.getChatClient(model, provider, any()) } returns chatClient

            val agent = agentService.findAgentByName("my-agent", context) as AgentSimple

            (agent.instructions ?: "") shouldNotContain "## User"
            verify(exactly = 0) { userService.findById(any()) }
        }

        "findAgentByName skips user block when user has no human-readable fields" {
            val userId = UUID.randomUUID()
            val user =
                User(
                    metadata = EntityMetadata(id = userId),
                    externalId = "opaque-objectid-123",
                    email = "",
                    firstname = null,
                    lastname = null,
                    bio = null,
                )
            val contextWithUser = AgentExecutionContext(namespaceId = namespaceId, caseId = caseId, userId = userId)
            val config = agentConfig(name = "my-agent", modelName = "sonnet")
            val model = modelConfig(alias = "sonnet")
            val provider = providerConfig()
            val chatClient = mockk<ChatClient>(relaxed = true)

            every { agentConfigService.findDeployedByNamespaceIdAndUserIdAndName(namespaceId, userId, "my-agent") } returns listOf(config)
            every { aiModelService.findAiModel(namespaceId, "sonnet") } returns model
            every { aiProviderService.getById(aiProviderId) } returns provider
            every { aiProviderService.resolveProvider(namespaceId, userId, "anthropic-prod") } returns provider
            every { chatClientProvider.getChatClient(model, provider, any()) } returns chatClient
            every { userService.findById(userId) } returns user

            val agent = agentService.findAgentByName("my-agent", contextWithUser) as AgentSimple

            // No readable data available: the ## User block must be omitted entirely
            // rather than injecting an opaque UUID that confuses the LLM.
            (agent.instructions ?: "") shouldNotContain "## User"
            (agent.instructions ?: "") shouldNotContain userId.toString()
            (agent.instructions ?: "") shouldNotContain "opaque-objectid-123"
        }

        // -------------------------------------------------------------------------
        // Tool filtering via agentConfig.integrations
        // -------------------------------------------------------------------------

        "findAgentByName passes null integrations to ToolResolverService when agentConfig has no integrations" {
            val config = agentConfig(name = "my-agent", modelName = "sonnet")
            val model = modelConfig(alias = "sonnet")
            val provider = providerConfig()
            val chatClient = mockk<ChatClient>(relaxed = true)

            every { agentConfigService.findByName(namespaceId, "my-agent") } returns config
            every { aiModelService.findAiModel(namespaceId, "sonnet") } returns model
            every { aiProviderService.getById(aiProviderId) } returns provider
            every { chatClientProvider.getChatClient(model, provider, any()) } returns chatClient

            agentService.findAgentByName("my-agent", context)

            verify(exactly = 1) {
                toolResolverService.resolveToolsForRun(
                    agentIntegrations = null,
                    context = any(),
                    allIntegrationConfigs = any(),
                    credentialProviderFactory = any(),
                )
            }
        }

        "findAgentByName passes integrations map to ToolResolverService when agentConfig has integrations" {
            val integrations = mapOf("FILES" to null, "JIRA_PROD" to listOf("GetIssue"))
            val config = agentConfig(name = "my-agent", modelName = "sonnet").copy(integrations = integrations)
            val model = modelConfig(alias = "sonnet")
            val provider = providerConfig()
            val chatClient = mockk<ChatClient>(relaxed = true)

            every { agentConfigService.findByName(namespaceId, "my-agent") } returns config
            every { aiModelService.findAiModel(namespaceId, "sonnet") } returns model
            every { aiProviderService.getById(aiProviderId) } returns provider
            every { chatClientProvider.getChatClient(model, provider, any()) } returns chatClient

            agentService.findAgentByName("my-agent", context)

            verify(exactly = 1) {
                toolResolverService.resolveToolsForRun(
                    agentIntegrations = integrations,
                    context = any(),
                    allIntegrationConfigs = any(),
                    credentialProviderFactory = any(),
                )
            }
        }

        // -------------------------------------------------------------------------
        // resolveAgentName
        // -------------------------------------------------------------------------

        "resolveAgentName returns AgentConfig name when one matches (no userId)" {
            val config = agentConfig(name = "my-agent")
            every { agentConfigService.findByName(namespaceId, "my-agent") } returns config

            agentService.resolveAgentName("my-agent", namespaceId) shouldBe "my-agent"

            verify(exactly = 0) { aiModelService.findAiModel(any(), any()) }
        }

        "resolveAgentName returns null when no AgentConfig matches (no userId)" {
            every { agentConfigService.findByName(namespaceId, "unknown") } returns null

            agentService.resolveAgentName("unknown", namespaceId) shouldBe null
        }

        "resolveAgentName with userId delegates to findAvailableByNamespaceIdAndUserId" {
            val userId = UUID.randomUUID()
            val config = agentConfig(name = "my-agent")
            every {
                agentConfigService.findDeployedByNamespaceIdAndUserIdAndName(namespaceId, userId, "my-agent")
            } returns listOf(config)

            agentService.resolveAgentName("my-agent", namespaceId, userId) shouldBe "my-agent"

            verify(exactly = 0) { agentConfigService.findByName(any(), any()) }
        }

        "resolveAgentName with userId returns null when agent not accessible to user" {
            val userId = UUID.randomUUID()
            every {
                agentConfigService.findDeployedByNamespaceIdAndUserIdAndName(namespaceId, userId, "restricted")
            } returns emptyList()

            agentService.resolveAgentName("restricted", namespaceId, userId) shouldBe null

            verify(exactly = 0) { agentConfigService.findByName(any(), any()) }
        }
    }
}
