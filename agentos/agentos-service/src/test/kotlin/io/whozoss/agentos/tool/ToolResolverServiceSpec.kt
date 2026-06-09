package io.whozoss.agentos.tool

import com.fasterxml.jackson.databind.JsonNode
import io.kotest.assertions.throwables.shouldThrow
import io.kotest.core.spec.style.StringSpec
import io.kotest.matchers.collections.shouldBeEmpty
import io.kotest.matchers.collections.shouldHaveSize
import io.kotest.matchers.shouldBe
import io.kotest.matchers.shouldNotBe
import io.mockk.every
import io.mockk.mockk
import io.whozoss.agentos.exception.ConfigNotFoundException
import io.whozoss.agentos.integrationConfig.IntegrationConfig
import io.whozoss.agentos.integrationConfig.IntegrationConfigService
import io.whozoss.agentos.integrationConfig.IntegrationTypeRegistry
import io.whozoss.agentos.reconciliation.ConfigMergeService
import io.whozoss.agentos.sdk.entity.EntityMetadata
import io.whozoss.agentos.sdk.tool.StandardTool
import io.whozoss.agentos.sdk.tool.ToolContext
import io.whozoss.agentos.sdk.tool.ToolExecutionResult
import io.whozoss.agentos.sdk.tool.ToolPlugin
import org.pf4j.PluginManager
import java.util.UUID

class ToolResolverServiceSpec :
    StringSpec({

        fun makeTool(name: String): StandardTool<Nothing> =
            object : StandardTool<Nothing> {
                override val name = name
                override val description = "Tool $name"
                override val inputSchema = """{"type":"object"}"""
                override val version = "1.0.0"
                override val paramType: Class<Nothing>? = null

                override suspend fun execute(
                    input: Nothing?,
                    context: ToolContext,
                ): ToolExecutionResult = ToolExecutionResult.success(name)
            }

        fun makeConfigLessPlugin(
            integrationType: String,
            vararg toolNames: String,
        ): ToolPlugin =
            object : ToolPlugin {
                override val integrationType = integrationType
                override val configSchema: JsonNode? = null

                override fun provideTools(
                    config: JsonNode?,
                    configName: String?,
                    context: ToolContext?,
                ): List<StandardTool<*>> = toolNames.map { makeTool(it) }
            }

        fun makeConfiguredPlugin(
            integrationType: String,
            vararg toolNames: String,
        ): ToolPlugin =
            object : ToolPlugin {
                override val integrationType = integrationType
                override val configSchema: JsonNode = mockk(relaxed = true)

                override fun provideTools(
                    config: JsonNode?,
                    configName: String?,
                    context: ToolContext?,
                ): List<StandardTool<*>> = toolNames.map { makeTool(it) }
            }

        fun initRegistry(plugins: List<ToolPlugin>): ToolRegistryService {
            val pluginManager = mockk<PluginManager>(relaxed = true)
            every { pluginManager.getExtensions(ToolPlugin::class.java) } returns plugins
            every { pluginManager.whichPlugin(any()) } returns null
            val integrationTypeRegistry = mockk<IntegrationTypeRegistry>(relaxed = true)
            val registry = ToolRegistryService(pluginManager, integrationTypeRegistry)
            registry.initialize()
            return registry
        }

        fun buildService(
            plugins: List<ToolPlugin> = emptyList(),
            configs: List<IntegrationConfig> = emptyList(),
        ): ToolResolverService {
            val registry = initRegistry(plugins)
            val integrationConfigService = mockk<IntegrationConfigService>(relaxed = true)
            every { integrationConfigService.findByParent(any()) } answers {
                val namespaceId = firstArg<UUID>()
                configs.filter { it.namespaceId == namespaceId }
            }
            val reconciliationService = mockk<ConfigMergeService<IntegrationConfig>>(relaxed = true)
            return ToolResolverService(registry, integrationConfigService, reconciliationService)
        }

        fun integrationConfig(
            namespaceId: UUID,
            name: String,
            integrationType: String,
        ) = IntegrationConfig(
            metadata = EntityMetadata(),
            namespaceId = namespaceId,
            name = name,
            integrationType = integrationType,
        )

        // -------------------------------------------------------------------------
        // Core lifecycle contract: fresh instances per run
        // -------------------------------------------------------------------------

        "resolveToolsForNamespace produces distinct tool instances on each call for config-less plugins" {
            val namespaceId = UUID.randomUUID()
            val plugin = makeConfigLessPlugin("DATETIME", "GetCurrentDateTime")
            val config = integrationConfig(namespaceId, "MY_DATETIME", "DATETIME")
            val service = buildService(plugins = listOf(plugin), configs = listOf(config))

            val tools1 = service.resolveToolsForNamespace(context = toolContext)
            val tools2 = service.resolveToolsForNamespace(context = toolContext)

            tools1 shouldHaveSize 1
            tools2 shouldHaveSize 1
            tools1.first() shouldNotBe tools2.first()
        }

        "resolveToolsForNamespace produces distinct tool instances on each call for configured plugins" {
            val namespaceId = UUID.randomUUID()
            val config = integrationConfig(namespaceId, "JIRA_PROD", "JIRA")
            val plugin = makeConfiguredPlugin("JIRA", "GetIssue")
            val service = buildService(plugins = listOf(plugin), configs = listOf(config))

            val tools1 = service.resolveToolsForNamespace(context = toolContext)
            val tools2 = service.resolveToolsForNamespace(context = toolContext)

            tools1 shouldHaveSize 1
            tools2 shouldHaveSize 1
            tools1.first() shouldNotBe tools2.first()
        }

        // -------------------------------------------------------------------------
        // Correctness: right tools are returned
        // -------------------------------------------------------------------------

        "resolveToolsForNamespace returns no tools when namespace has no IntegrationConfig" {
            val plugin = makeConfigLessPlugin("DATETIME", "GetCurrentDateTime")
            val service = buildService(plugins = listOf(plugin))
            val namespaceId = UUID.randomUUID()

            val tools = service.resolveToolsForNamespace(context = toolContext)

            tools.shouldBeEmpty()
        }

        "resolveToolsForNamespace returns config-less tools when a matching IntegrationConfig exists" {
            val namespaceId = UUID.randomUUID()
            val plugin = makeConfigLessPlugin("DATETIME", "GetCurrentDateTime", "GetCurrentDate")
            val config = integrationConfig(namespaceId, "MY_DATETIME", "DATETIME")
            val service = buildService(plugins = listOf(plugin), configs = listOf(config))

            val tools = service.resolveToolsForNamespace(context = toolContext)

            tools shouldHaveSize 2
            tools.map { it.name }.toSet() shouldBe setOf("GetCurrentDateTime", "GetCurrentDate")
        }

        "resolveToolsForNamespace returns configured tools matching the namespace" {
            val namespaceId = UUID.randomUUID()
            val config = integrationConfig(namespaceId, "JIRA_PROD", "JIRA")
            val plugin = makeConfiguredPlugin("JIRA", "GetIssue", "SearchIssues")
            val service = buildService(plugins = listOf(plugin), configs = listOf(config))

            val tools = service.resolveToolsForNamespace(context = toolContext)

            tools shouldHaveSize 2
            tools.map { it.name }.toSet() shouldBe setOf("GetIssue", "SearchIssues")
        }

        "resolveToolsForNamespace combines config-less and configured tools via their IntegrationConfigs" {
            val namespaceId = UUID.randomUUID()
            val datetimeConfig = integrationConfig(namespaceId, "MY_DATETIME", "DATETIME")
            val jiraConfig = integrationConfig(namespaceId, "JIRA_PROD", "JIRA")
            val configLessPlugin = makeConfigLessPlugin("DATETIME", "GetCurrentDateTime")
            val configuredPlugin = makeConfiguredPlugin("JIRA", "GetIssue")
            val service =
                buildService(
                    plugins = listOf(configLessPlugin, configuredPlugin),
                    configs = listOf(datetimeConfig, jiraConfig),
                )

            val tools = service.resolveToolsForNamespace(context = toolContext)

            tools shouldHaveSize 2
            tools.map { it.name }.toSet() shouldBe setOf("GetCurrentDateTime", "GetIssue")
        }

        "resolveToolsForNamespace returns no tools for a namespace with no matching IntegrationConfig" {
            val namespaceId = UUID.randomUUID()
            val otherNamespaceId = UUID.randomUUID()
            val configInOtherNamespace = integrationConfig(otherNamespaceId, "JIRA_PROD", "JIRA")
            val configuredPlugin = makeConfiguredPlugin("JIRA", "GetIssue")
            val service =
                buildService(
                    plugins = listOf(configuredPlugin),
                    configs = listOf(configInOtherNamespace),
                )

            val tools = service.resolveToolsForNamespace(context = toolContext)

            tools.shouldBeEmpty()
        }

        // -------------------------------------------------------------------------
        // Agent integrations filter — always by IntegrationConfig.name
        // -------------------------------------------------------------------------

        "resolveToolsForNamespace with agentIntegrations null returns all tools" {
            val namespaceId = UUID.randomUUID()
            val jiraConfig = integrationConfig(namespaceId, "JIRA_PROD", "JIRA")
            val datetimeConfig = integrationConfig(namespaceId, "MY_DATETIME", "DATETIME")
            val configuredPlugin = makeConfiguredPlugin("JIRA", "GetIssue")
            val configLessPlugin = makeConfigLessPlugin("DATETIME", "GetCurrentDateTime")
            val service =
                buildService(
                    plugins = listOf(configuredPlugin, configLessPlugin),
                    configs = listOf(jiraConfig, datetimeConfig),
                )

            val tools = service.resolveToolsForNamespace(context = toolContext)

            tools shouldHaveSize 2
        }

        "resolveToolsForNamespace with agentIntegrations filters by IntegrationConfig name, not integrationType" {
            val namespaceId = UUID.randomUUID()
            val jiraConfig = integrationConfig(namespaceId, "JIRA_PROD", "JIRA")
            val datetimeConfig = integrationConfig(namespaceId, "MY_DATETIME", "DATETIME")
            val configuredPlugin = makeConfiguredPlugin("JIRA", "GetIssue")
            val configLessPlugin = makeConfigLessPlugin("DATETIME", "GetCurrentDateTime")
            val service =
                buildService(
                    plugins = listOf(configuredPlugin, configLessPlugin),
                    configs = listOf(jiraConfig, datetimeConfig),
                )

            val tools =
                service.resolveToolsForNamespace(
                    agentIntegrations = mapOf("JIRA_PROD" to null),
                    context = toolContext,
                )

            tools shouldHaveSize 1
            tools.first().name shouldBe "GetIssue"
        }

        "resolveToolsForNamespace with agentIntegrations excludes config-less tools when their config name is absent" {
            val namespaceId = UUID.randomUUID()
            val datetimeConfig = integrationConfig(namespaceId, "MY_DATETIME", "DATETIME")
            val jiraConfig = integrationConfig(namespaceId, "JIRA_PROD", "JIRA")
            val configLessPlugin = makeConfigLessPlugin("DATETIME", "GetCurrentDateTime")
            val configuredPlugin = makeConfiguredPlugin("JIRA", "GetIssue")
            val service =
                buildService(
                    plugins = listOf(configLessPlugin, configuredPlugin),
                    configs = listOf(datetimeConfig, jiraConfig),
                )

            val tools =
                service.resolveToolsForNamespace(
                    agentIntegrations = mapOf("JIRA_PROD" to null),
                    context = toolContext,
                )

            tools shouldHaveSize 1
            tools.first().name shouldBe "GetIssue"
        }

        "resolveToolsForNamespace with non-null allowed list filters tools within an integration" {
            val namespaceId = UUID.randomUUID()
            val jiraConfig = integrationConfig(namespaceId, "JIRA_PROD", "JIRA")
            val configuredPlugin = makeConfiguredPlugin("JIRA", "GetIssue", "SearchIssues", "CreateIssue")
            val service = buildService(plugins = listOf(configuredPlugin), configs = listOf(jiraConfig))

            val tools =
                service.resolveToolsForNamespace(
                    agentIntegrations = mapOf("JIRA_PROD" to listOf("GetIssue", "SearchIssues")),
                    context = toolContext,
                )

            tools shouldHaveSize 2
            tools.map { it.name }.toSet() shouldBe setOf("GetIssue", "SearchIssues")
        }

        "resolveToolsForNamespace with null allowed list returns all tools from that integration" {
            val namespaceId = UUID.randomUUID()
            val jiraConfig = integrationConfig(namespaceId, "JIRA_PROD", "JIRA")
            val configuredPlugin = makeConfiguredPlugin("JIRA", "GetIssue", "SearchIssues", "CreateIssue")
            val service = buildService(plugins = listOf(configuredPlugin), configs = listOf(jiraConfig))

            val tools =
                service.resolveToolsForNamespace(
                    agentIntegrations = mapOf("JIRA_PROD" to null),
                    context = toolContext,
                )

            tools shouldHaveSize 3
        }

        // -------------------------------------------------------------------------
        // isToolAllowed helper
        // -------------------------------------------------------------------------

        "isToolAllowed returns true when allowedNames is null" {
            val service = buildService()
            service.isToolAllowed("ReadFile", "FILES", null) shouldBe true
        }

        "isToolAllowed returns true for exact name match" {
            val service = buildService()
            service.isToolAllowed("ReadFile", "FILES", listOf("ReadFile", "ListFiles")) shouldBe true
        }

        "isToolAllowed returns false when name not in allowed list" {
            val service = buildService()
            service.isToolAllowed("EditFile", "FILES", listOf("ReadFile", "ListFiles")) shouldBe false
        }

        "isToolAllowed matches prefixed tool name via KEY__suffix convention" {
            val service = buildService()
            service.isToolAllowed("JIRA_PROD__GetIssue", "JIRA_PROD", listOf("GetIssue")) shouldBe true
        }

        "isToolAllowed does not match wrong prefix" {
            val service = buildService()
            service.isToolAllowed("JIRA_STAGING__GetIssue", "JIRA_PROD", listOf("GetIssue")) shouldBe false
        }

        // -------------------------------------------------------------------------
        // resolveToolsForRun — story 6.4 AC1-AC4 (6+ scenarios)
        // -------------------------------------------------------------------------

        fun buildServiceForRun(
            plugins: List<ToolPlugin> = emptyList(),
            sharedConfigs: List<IntegrationConfig> = emptyList(),
            userOverrides: List<IntegrationConfig> = emptyList(),
            reconciledConfigs: Map<String, IntegrationConfig> = emptyMap(),
        ): ToolResolverService {
            val registry = initRegistry(plugins)
            val integrationConfigService = mockk<IntegrationConfigService>(relaxed = true)
            every { integrationConfigService.findByNamespaceShared(any()) } returns sharedConfigs
            every { integrationConfigService.findByUserId(any()) } returns userOverrides

            val reconciliationService = mockk<ConfigMergeService<IntegrationConfig>>(relaxed = true)
            every { reconciliationService.resolve(any(), any(), any()) } answers {
                val name = thirdArg<String>()
                reconciledConfigs[name]
                    ?: throw ConfigNotFoundException(firstArg(), secondArg(), name)
            }

            return ToolResolverService(registry, integrationConfigService, reconciliationService)
        }

        "resolveToolsForRun returns empty when no IntegrationConfig exists, even if config-less plugins are loaded" {
            val namespaceId = UUID.randomUUID()
            val userId = UUID.randomUUID()
            val plugin = makeConfigLessPlugin("DATETIME", "GetCurrentDateTime")
            val service = buildServiceForRun(plugins = listOf(plugin))

            val tools = service.resolveToolsForRun()

            tools.shouldBeEmpty()
        }

        "resolveToolsForRun resolves tools from namespace-shared config only (no user override)" {
            val namespaceId = UUID.randomUUID()
            val userId = UUID.randomUUID()
            val plugin = makeConfiguredPlugin("JIRA", "GetIssue")
            val shared = IntegrationConfig(metadata = EntityMetadata(), namespaceId = namespaceId, name = "jira", integrationType = "JIRA")
            val reconciled = shared
            val service =
                buildServiceForRun(
                    plugins = listOf(plugin),
                    sharedConfigs = listOf(shared),
                    reconciledConfigs = mapOf("jira" to reconciled),
                )

            val tools = service.resolveToolsForRun()

            tools shouldHaveSize 1
            tools.first().name shouldBe "GetIssue"
        }

        "resolveToolsForRun resolves tools from user-global override only (no namespace config)" {
            val namespaceId = UUID.randomUUID()
            val userId = UUID.randomUUID()
            val plugin = makeConfiguredPlugin("GITHUB", "CreatePR")
            val userGlobal = IntegrationConfig(metadata = EntityMetadata(), userId = userId, name = "github", integrationType = "GITHUB")
            val reconciled = userGlobal
            val service =
                buildServiceForRun(
                    plugins = listOf(plugin),
                    userOverrides = listOf(userGlobal),
                    reconciledConfigs = mapOf("github" to reconciled),
                )

            val tools = service.resolveToolsForRun()

            tools shouldHaveSize 1
            tools.first().name shouldBe "CreatePR"
        }

        "resolveToolsForRun 3-tier fold: user×namespace override applied" {
            val namespaceId = UUID.randomUUID()
            val userId = UUID.randomUUID()
            val plugin = makeConfiguredPlugin("JIRA", "GetIssue")
            val shared = IntegrationConfig(metadata = EntityMetadata(), namespaceId = namespaceId, name = "jira", integrationType = "JIRA")
            val userNs =
                IntegrationConfig(
                    metadata = EntityMetadata(),
                    namespaceId = namespaceId,
                    userId = userId,
                    name = "jira",
                    integrationType = "JIRA",
                )
            val reconciled = userNs
            val service =
                buildServiceForRun(
                    plugins = listOf(plugin),
                    sharedConfigs = listOf(shared),
                    userOverrides = listOf(userNs),
                    reconciledConfigs = mapOf("jira" to reconciled),
                )

            val tools = service.resolveToolsForRun()

            tools shouldHaveSize 1
        }

        "resolveToolsForRun fails fast when a namespace-shared name fails reconciliation (NFR-REL-1)" {
            val namespaceId = UUID.randomUUID()
            val userId = UUID.randomUUID()
            val plugin = makeConfiguredPlugin("GITHUB", "CreatePR")
            val shared1 = IntegrationConfig(metadata = EntityMetadata(), namespaceId = namespaceId, name = "jira", integrationType = "JIRA")
            val shared2 =
                IntegrationConfig(metadata = EntityMetadata(), namespaceId = namespaceId, name = "github", integrationType = "GITHUB")
            val service =
                buildServiceForRun(
                    plugins = listOf(plugin),
                    sharedConfigs = listOf(shared1, shared2),
                    reconciledConfigs = mapOf("github" to shared2),
                )

            shouldThrow<ConfigNotFoundException> {
                service.resolveToolsForRun()
            }
        }

        "resolveToolsForRun silently skips dormant user overrides whose name has no shared config (FR30)" {
            val namespaceId = UUID.randomUUID()
            val userId = UUID.randomUUID()
            val plugin = makeConfiguredPlugin("GITHUB", "CreatePR")
            val shared =
                IntegrationConfig(metadata = EntityMetadata(), namespaceId = namespaceId, name = "github", integrationType = "GITHUB")
            val dormantOverride =
                IntegrationConfig(
                    metadata = EntityMetadata(),
                    namespaceId = namespaceId,
                    userId = userId,
                    name = "ghost-name",
                    integrationType = "JIRA",
                )
            val service =
                buildServiceForRun(
                    plugins = listOf(plugin),
                    sharedConfigs = listOf(shared),
                    userOverrides = listOf(dormantOverride),
                    reconciledConfigs = mapOf("github" to shared),
                )

            val tools = service.resolveToolsForRun()

            tools shouldHaveSize 1
            tools.first().name shouldBe "CreatePR"
        }

        "resolveToolsForRun dormant override on different namespace is filtered out (AC4)" {
            val ns1 = UUID.randomUUID()
            val ns2 = UUID.randomUUID()
            val userId = UUID.randomUUID()
            val plugin = makeConfiguredPlugin("JIRA", "GetIssue")
            val overrideForNs2 =
                IntegrationConfig(metadata = EntityMetadata(), namespaceId = ns2, userId = userId, name = "jira", integrationType = "JIRA")
            val service =
                buildServiceForRun(
                    plugins = listOf(plugin),
                    userOverrides = listOf(overrideForNs2),
                )

            val tools = service.resolveToolsForRun()

            tools shouldHaveSize 0
        }

        // -------------------------------------------------------------------------
        // RedirectToolPlugin: ToolContext must be forwarded so namespaceId is available
        // -------------------------------------------------------------------------

        "resolveToolsForNamespace passes namespaceId via ToolContext to plugins that need it" {
            // Regression: ToolResolverService called provideTools(config, name) without a ToolContext.
            // RedirectToolPlugin requires context?.namespaceId to resolve eligible agents;
            // without it, it returns emptyList() regardless of the config patterns.
            val namespaceId = UUID.randomUUID()
            var capturedContext: ToolContext? = null
            val contextCapturingPlugin =
                object : ToolPlugin {
                    override val integrationType = "REDIRECT"
                    override val configSchema: JsonNode = mockk(relaxed = true)

                    override fun provideTools(
                        config: JsonNode?,
                        configName: String?,
                        context: ToolContext?,
                    ): List<StandardTool<*>> {
                        capturedContext = context
                        // Simulate what RedirectToolPlugin does: return empty when context is null
                        if (context?.namespaceId == null) return emptyList()
                        return listOf(makeTool("redirect"))
                    }
                }
            val config = integrationConfig(namespaceId, "REDIRECT_all", "REDIRECT")
            val service = buildService(plugins = listOf(contextCapturingPlugin), configs = listOf(config))

            val tools = service.resolveToolsForNamespace(context = toolContext)

            capturedContext shouldNotBe null
            capturedContext!!.namespaceId shouldBe namespaceId
            tools shouldHaveSize 1
        }

        "resolveToolsForRun passes namespaceId via ToolContext to plugins that need it" {
            // Same regression for the user-scoped resolution path.
            val namespaceId = UUID.randomUUID()
            val userId = UUID.randomUUID()
            var capturedContext: ToolContext? = null
            val contextCapturingPlugin =
                object : ToolPlugin {
                    override val integrationType = "REDIRECT"
                    override val configSchema: JsonNode = mockk(relaxed = true)

                    override fun provideTools(
                        config: JsonNode?,
                        configName: String?,
                        context: ToolContext?,
                    ): List<StandardTool<*>> {
                        capturedContext = context
                        if (context?.namespaceId == null) return emptyList()
                        return listOf(makeTool("redirect"))
                    }
                }
            val shared =
                IntegrationConfig(
                    metadata = EntityMetadata(),
                    namespaceId = namespaceId,
                    name = "REDIRECT_all",
                    integrationType = "REDIRECT",
                )
            val service =
                buildServiceForRun(
                    plugins = listOf(contextCapturingPlugin),
                    sharedConfigs = listOf(shared),
                    reconciledConfigs = mapOf("REDIRECT_all" to shared),
                )

            val tools = service.resolveToolsForRun()

            capturedContext shouldNotBe null
            capturedContext!!.namespaceId shouldBe namespaceId
            tools shouldHaveSize 1
        }

        "resolveToolsForRun combines config-less and configured tools when both have IntegrationConfigs" {
            val namespaceId = UUID.randomUUID()
            val userId = UUID.randomUUID()
            val configLessPlugin = makeConfigLessPlugin("DATETIME", "GetCurrentDateTime")
            val configuredPlugin = makeConfiguredPlugin("JIRA", "GetIssue")
            val datetimeConfig =
                IntegrationConfig(metadata = EntityMetadata(), namespaceId = namespaceId, name = "datetime", integrationType = "DATETIME")
            val jiraConfig =
                IntegrationConfig(metadata = EntityMetadata(), namespaceId = namespaceId, name = "jira", integrationType = "JIRA")
            val service =
                buildServiceForRun(
                    plugins = listOf(configLessPlugin, configuredPlugin),
                    sharedConfigs = listOf(datetimeConfig, jiraConfig),
                    reconciledConfigs = mapOf("datetime" to datetimeConfig, "jira" to jiraConfig),
                )

            val tools = service.resolveToolsForRun()

            tools shouldHaveSize 2
            tools.map { it.name }.toSet() shouldBe setOf("GetCurrentDateTime", "GetIssue")
        }
    })
