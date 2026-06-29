package io.whozoss.agentos.agent

import com.fasterxml.jackson.databind.ObjectMapper
import io.kotest.assertions.throwables.shouldThrow
import io.kotest.core.spec.style.StringSpec
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
import io.whozoss.agentos.aiModel.AiModelService
import io.whozoss.agentos.aiProvider.AiProviderService
import io.whozoss.agentos.caseEvent.CaseEventService
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
import io.whozoss.agentos.sdk.tool.ToolPlugin
import io.whozoss.agentos.metrics.ToolMetricsService
import io.whozoss.agentos.tool.ToolRegistryService
import io.whozoss.agentos.tool.ToolResolverService
import io.whozoss.agentos.user.User
import io.whozoss.agentos.user.UserService
import org.springframework.ai.chat.client.ChatClient
import java.util.UUID

class AgentServiceImplUnitSpec : StringSpec() {
    private val chatClientProvider: ChatClientProvider = mockk()
    private val toolResolverService: ToolResolverService = mockk()
    private val aiModelService: AiModelService = mockk()
    private val aiProviderService: AiProviderService = mockk()
    private val namespaceService: NamespaceService = mockk()
    private val integrationConfigService: IntegrationConfigService = mockk(relaxed = true)
    private val userService: UserService = mockk(relaxed = true)
    private val aiProviderReconciliationService: ConfigMergeService<AiProvider> =
        mockk(relaxed = true)
    private val agentConfigService: AgentConfigService = mockk()
    private val intentionGenerator: AgentIntentionGenerator = mockk(relaxed = true)
    private val confirmationManager: ConfirmationManager = mockk(relaxed = true)
    private val testObjectMapper = ObjectMapper()
    private val toolRegistryService: ToolRegistryService = mockk(relaxed = true)
    private val toolMetricsService: ToolMetricsService = mockk(relaxed = true)
    private val caseEventService: CaseEventService = mockk(relaxed = true)
    private val agentService =
        AgentServiceImpl(
            chatClientProvider,
            toolResolverService,
            aiModelService,
            aiProviderService,
            namespaceService,
            integrationConfigService,
            userService,
            aiProviderReconciliationService,
            agentConfigService,
            intentionGenerator,
            confirmationManager,
            testObjectMapper,
            toolRegistryService,
            toolMetricsService,
            caseEventService,
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
        every { toolResolverService.resolveToolsForRun(agentIntegrations = any(), context = any(), allIntegrationConfigs = any()) } returns emptyList()

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
            every { chatClientProvider.getChatClient(model, provider) } returns chatClient

            val agent = agentService.findAgentByName("my-agent", context)

            agent.name shouldBe "my-agent"
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
            every { chatClientProvider.getChatClient(model, provider) } returns chatClient

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

        "findAgentByName includes namespace name and description in system prompt" {
            val config = agentConfig(name = "my-agent", modelName = "sonnet")
            val model = modelConfig(alias = "sonnet")
            val provider = providerConfig()
            val chatClient = mockk<ChatClient>(relaxed = true)

            every { agentConfigService.findByName(namespaceId, "my-agent") } returns config
            every { aiModelService.findAiModel(namespaceId, "sonnet") } returns model
            every { aiProviderService.getById(aiProviderId) } returns provider
            every { chatClientProvider.getChatClient(model, provider) } returns chatClient

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
            every { chatClientProvider.getChatClient(model, provider) } returns chatClient

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
            every { chatClientProvider.getChatClient(model, provider) } returns chatClient

            val agent = agentService.findAgentByName("my-agent", context) as AgentSimple

            (agent.instructions ?: "") shouldNotContain "## Integrations"
            (agent.instructions ?: "") shouldNotContain "JIRA_PROD"
        }

        "findAgentByName appends integrations block only for configs listed in agentConfig.integrations" {
            val localIntegrationService = mockk<IntegrationConfigService>()
            val localService =
                AgentServiceImpl(
                    chatClientProvider,
                    toolResolverService,
                    aiModelService,
                    aiProviderService,
                    namespaceService,
                    localIntegrationService,
                    userService,
                    aiProviderReconciliationService,
                    agentConfigService,
                    intentionGenerator,
                    confirmationManager,
                    testObjectMapper,
                    toolRegistryService,
                    toolMetricsService,
                    caseEventService,
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
            every { chatClientProvider.getChatClient(model, provider) } returns chatClient

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
            every { chatClientProvider.getChatClient(model, provider) } returns chatClient

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
            every { chatClientProvider.getChatClient(model, provider) } returns chatClient

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
            every { chatClientProvider.getChatClient(model, provider) } returns chatClient

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
            every { chatClientProvider.getChatClient(model, provider) } returns chatClient

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
            every { chatClientProvider.getChatClient(model, provider) } returns chatClient

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

            every { aiProviderService.getById(aiProviderId) } returns provider
            every { aiProviderReconciliationService.resolve(namespaceId, userId, "anthropic-prod") } returns provider
            every { chatClientProvider.getChatClient(model, provider) } returns chatClient
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

            every { agentConfigService.findByName(namespaceId, "my-agent") } returns config
            every { aiModelService.findAiModel(namespaceId, "sonnet") } returns model

            every { aiProviderService.getById(aiProviderId) } returns provider
            every { aiProviderReconciliationService.resolve(namespaceId, userId, "anthropic-prod") } returns provider
            every { chatClientProvider.getChatClient(model, provider) } returns chatClient
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
            every { chatClientProvider.getChatClient(model, provider) } returns chatClient

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

            every { agentConfigService.findByName(namespaceId, "my-agent") } returns config
            every { aiModelService.findAiModel(namespaceId, "sonnet") } returns model
            every { aiProviderService.getById(aiProviderId) } returns provider
            every { aiProviderReconciliationService.resolve(namespaceId, userId, "anthropic-prod") } returns provider
            every { chatClientProvider.getChatClient(model, provider) } returns chatClient
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
            every { chatClientProvider.getChatClient(model, provider) } returns chatClient

            agentService.findAgentByName("my-agent", context)

            verify(exactly = 1) {
                toolResolverService.resolveToolsForRun(
                    agentIntegrations = null,
                    context = any(),
                    allIntegrationConfigs = any(),
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
            every { chatClientProvider.getChatClient(model, provider) } returns chatClient

            agentService.findAgentByName("my-agent", context)

            verify(exactly = 1) {
                toolResolverService.resolveToolsForRun(
                    agentIntegrations = integrations,
                    context = any(),
                    allIntegrationConfigs = any(),
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
                agentConfigService.findAvailableByNamespaceIdAndUserId(namespaceId, userId, "my-agent")
            } returns listOf(config)

            agentService.resolveAgentName("my-agent", namespaceId, userId) shouldBe "my-agent"

            verify(exactly = 0) { agentConfigService.findByName(any(), any()) }
        }

        "resolveAgentName with userId returns null when agent not accessible to user" {
            val userId = UUID.randomUUID()
            every {
                agentConfigService.findAvailableByNamespaceIdAndUserId(namespaceId, userId, "restricted")
            } returns emptyList()

            agentService.resolveAgentName("restricted", namespaceId, userId) shouldBe null

            verify(exactly = 0) { agentConfigService.findByName(any(), any()) }
        }
    }
}
