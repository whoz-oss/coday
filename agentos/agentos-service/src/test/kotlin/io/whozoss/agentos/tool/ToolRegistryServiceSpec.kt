package io.whozoss.agentos.tool

import com.fasterxml.jackson.databind.JsonNode
import io.kotest.assertions.throwables.shouldThrow
import io.kotest.core.spec.style.StringSpec
import io.kotest.matchers.collections.shouldBeEmpty
import io.kotest.matchers.collections.shouldHaveSize
import io.kotest.matchers.shouldBe
import io.kotest.matchers.shouldNotBe
import io.whozoss.agentos.reconciliation.ConfigNotFoundException
import io.mockk.every
import io.mockk.mockk
import io.whozoss.agentos.integrationConfig.IntegrationConfig
import io.whozoss.agentos.integrationConfig.IntegrationConfigService
import io.whozoss.agentos.integrationConfig.IntegrationTypeRegistry
import io.whozoss.agentos.reconciliation.ConfigReconciliationService
import io.whozoss.agentos.sdk.entity.EntityMetadata
import io.whozoss.agentos.sdk.tool.StandardTool
import io.whozoss.agentos.sdk.tool.ToolContext
import io.whozoss.agentos.sdk.tool.ToolPlugin
import org.pf4j.PluginManager
import java.util.UUID

/**
 * Tests for [ToolRegistryService].
 *
 * Key design invariants:
 * 1. **All tools are resolved via [IntegrationConfig]** — a plugin (config-less or not)
 *    only contributes tools when a matching [IntegrationConfig] exists in the namespace.
 *    The filter key is always [IntegrationConfig.name], never [ToolPlugin.integrationType].
 * 2. **Fresh instances per run** — every [resolveToolsForNamespace] call produces new
 *    tool instances; no instance outlives its agent run.
 */
class ToolRegistryServiceSpec : StringSpec({

    fun makeTool(name: String): StandardTool<Nothing> =
        object : StandardTool<Nothing> {
            override val name = name
            override val description = "Tool $name"
            override val inputSchema = """{"type":"object"}"""
            override val version = "1.0.0"
            override val paramType: Class<Nothing>? = null
            override fun execute(input: Nothing?, context: ToolContext): String = name
        }

    fun makeConfigLessPlugin(integrationType: String, vararg toolNames: String): ToolPlugin =
        object : ToolPlugin {
            override val integrationType = integrationType
            override val configSchema: JsonNode? = null
            override fun provideTools(config: JsonNode?, configName: String?): List<StandardTool<*>> =
                toolNames.map { makeTool(it) }
        }

    fun makeConfiguredPlugin(integrationType: String, vararg toolNames: String): ToolPlugin =
        object : ToolPlugin {
            override val integrationType = integrationType
            override val configSchema: JsonNode = mockk(relaxed = true)
            override fun provideTools(config: JsonNode?, configName: String?): List<StandardTool<*>> =
                toolNames.map { makeTool(it) }
        }

    fun buildService(
        plugins: List<ToolPlugin> = emptyList(),
        configs: List<IntegrationConfig> = emptyList(),
    ): ToolRegistryService {
        val pluginManager = mockk<org.pf4j.PluginManager>(relaxed = true)
        every { pluginManager.getExtensions(ToolPlugin::class.java) } returns plugins
        every { pluginManager.whichPlugin(any()) } returns null

        val integrationConfigService = mockk<IntegrationConfigService>(relaxed = true)
        every { integrationConfigService.findByParent(any()) } answers {
            val namespaceId = firstArg<UUID>()
            configs.filter { it.namespaceId == namespaceId }
        }

        val integrationTypeRegistry = mockk<IntegrationTypeRegistry>(relaxed = true)
        val reconciliationService = mockk<ConfigReconciliationService<IntegrationConfig>>(relaxed = true)

        val service = ToolRegistryService(pluginManager, integrationConfigService, integrationTypeRegistry, reconciliationService)
        service.initialize()
        return service
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

        val tools1 = service.resolveToolsForNamespace(namespaceId)
        val tools2 = service.resolveToolsForNamespace(namespaceId)

        tools1 shouldHaveSize 1
        tools2 shouldHaveSize 1
        tools1.first() shouldNotBe tools2.first()
    }

    "resolveToolsForNamespace produces distinct tool instances on each call for configured plugins" {
        val namespaceId = UUID.randomUUID()
        val config = integrationConfig(namespaceId, "JIRA_PROD", "JIRA")
        val plugin = makeConfiguredPlugin("JIRA", "GetIssue")
        val service = buildService(plugins = listOf(plugin), configs = listOf(config))

        val tools1 = service.resolveToolsForNamespace(namespaceId)
        val tools2 = service.resolveToolsForNamespace(namespaceId)

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

        val tools = service.resolveToolsForNamespace(namespaceId)

        tools.shouldBeEmpty()
    }

    "resolveToolsForNamespace returns config-less tools when a matching IntegrationConfig exists" {
        val namespaceId = UUID.randomUUID()
        val plugin = makeConfigLessPlugin("DATETIME", "GetCurrentDateTime", "GetCurrentDate")
        val config = integrationConfig(namespaceId, "MY_DATETIME", "DATETIME")
        val service = buildService(plugins = listOf(plugin), configs = listOf(config))

        val tools = service.resolveToolsForNamespace(namespaceId)

        tools shouldHaveSize 2
        tools.map { it.name }.toSet() shouldBe setOf("GetCurrentDateTime", "GetCurrentDate")
    }

    "resolveToolsForNamespace returns configured tools matching the namespace" {
        val namespaceId = UUID.randomUUID()
        val config = integrationConfig(namespaceId, "JIRA_PROD", "JIRA")
        val plugin = makeConfiguredPlugin("JIRA", "GetIssue", "SearchIssues")
        val service = buildService(plugins = listOf(plugin), configs = listOf(config))

        val tools = service.resolveToolsForNamespace(namespaceId)

        tools shouldHaveSize 2
        tools.map { it.name }.toSet() shouldBe setOf("GetIssue", "SearchIssues")
    }

    "resolveToolsForNamespace combines config-less and configured tools via their IntegrationConfigs" {
        val namespaceId = UUID.randomUUID()
        val datetimeConfig = integrationConfig(namespaceId, "MY_DATETIME", "DATETIME")
        val jiraConfig = integrationConfig(namespaceId, "JIRA_PROD", "JIRA")
        val configLessPlugin = makeConfigLessPlugin("DATETIME", "GetCurrentDateTime")
        val configuredPlugin = makeConfiguredPlugin("JIRA", "GetIssue")
        val service = buildService(
            plugins = listOf(configLessPlugin, configuredPlugin),
            configs = listOf(datetimeConfig, jiraConfig),
        )

        val tools = service.resolveToolsForNamespace(namespaceId)

        tools shouldHaveSize 2
        tools.map { it.name }.toSet() shouldBe setOf("GetCurrentDateTime", "GetIssue")
    }

    "resolveToolsForNamespace returns no tools for a namespace with no matching IntegrationConfig" {
        val namespaceId = UUID.randomUUID()
        val otherNamespaceId = UUID.randomUUID()
        val configInOtherNamespace = integrationConfig(otherNamespaceId, "JIRA_PROD", "JIRA")
        val configuredPlugin = makeConfiguredPlugin("JIRA", "GetIssue")
        val service = buildService(
            plugins = listOf(configuredPlugin),
            configs = listOf(configInOtherNamespace),
        )

        val tools = service.resolveToolsForNamespace(namespaceId)

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
        val service = buildService(
            plugins = listOf(configuredPlugin, configLessPlugin),
            configs = listOf(jiraConfig, datetimeConfig),
        )

        val tools = service.resolveToolsForNamespace(namespaceId, agentIntegrations = null)

        tools shouldHaveSize 2
    }

    "resolveToolsForNamespace with agentIntegrations filters by IntegrationConfig name, not integrationType" {
        val namespaceId = UUID.randomUUID()
        val jiraConfig = integrationConfig(namespaceId, "JIRA_PROD", "JIRA")
        val datetimeConfig = integrationConfig(namespaceId, "MY_DATETIME", "DATETIME")
        val configuredPlugin = makeConfiguredPlugin("JIRA", "GetIssue")
        val configLessPlugin = makeConfigLessPlugin("DATETIME", "GetCurrentDateTime")
        val service = buildService(
            plugins = listOf(configuredPlugin, configLessPlugin),
            configs = listOf(jiraConfig, datetimeConfig),
        )

        // Filter by config name "JIRA_PROD", not by integrationType "JIRA"
        val tools = service.resolveToolsForNamespace(
            namespaceId,
            agentIntegrations = mapOf("JIRA_PROD" to null),
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
        val service = buildService(
            plugins = listOf(configLessPlugin, configuredPlugin),
            configs = listOf(datetimeConfig, jiraConfig),
        )

        // Agent only lists JIRA_PROD — MY_DATETIME is not in the filter
        val tools = service.resolveToolsForNamespace(
            namespaceId,
            agentIntegrations = mapOf("JIRA_PROD" to null),
        )

        tools shouldHaveSize 1
        tools.first().name shouldBe "GetIssue"
    }

    "resolveToolsForNamespace with non-null allowed list filters tools within an integration" {
        val namespaceId = UUID.randomUUID()
        val jiraConfig = integrationConfig(namespaceId, "JIRA_PROD", "JIRA")
        val configuredPlugin = makeConfiguredPlugin("JIRA", "GetIssue", "SearchIssues", "CreateIssue")
        val service = buildService(plugins = listOf(configuredPlugin), configs = listOf(jiraConfig))

        val tools = service.resolveToolsForNamespace(
            namespaceId,
            agentIntegrations = mapOf("JIRA_PROD" to listOf("GetIssue", "SearchIssues")),
        )

        tools shouldHaveSize 2
        tools.map { it.name }.toSet() shouldBe setOf("GetIssue", "SearchIssues")
    }

    "resolveToolsForNamespace with null allowed list returns all tools from that integration" {
        val namespaceId = UUID.randomUUID()
        val jiraConfig = integrationConfig(namespaceId, "JIRA_PROD", "JIRA")
        val configuredPlugin = makeConfiguredPlugin("JIRA", "GetIssue", "SearchIssues", "CreateIssue")
        val service = buildService(plugins = listOf(configuredPlugin), configs = listOf(jiraConfig))

        val tools = service.resolveToolsForNamespace(
            namespaceId,
            agentIntegrations = mapOf("JIRA_PROD" to null),
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
    ): ToolRegistryService {
        val pluginManager = mockk<PluginManager>(relaxed = true)
        every { pluginManager.getExtensions(ToolPlugin::class.java) } returns plugins
        every { pluginManager.whichPlugin(any()) } returns null

        val integrationConfigService = mockk<IntegrationConfigService>(relaxed = true)
        every { integrationConfigService.findByNamespaceShared(any()) } returns sharedConfigs
        every { integrationConfigService.findByUserId(any()) } returns userOverrides

        val integrationTypeRegistry = mockk<IntegrationTypeRegistry>(relaxed = true)
        val reconciliationService = mockk<ConfigReconciliationService<IntegrationConfig>>(relaxed = true)
        every { reconciliationService.resolve(any(), any(), any()) } answers {
            val name = thirdArg<String>()
            reconciledConfigs[name]
                ?: throw io.whozoss.agentos.reconciliation.ConfigNotFoundException(firstArg(), secondArg(), name)
        }

        val service = ToolRegistryService(pluginManager, integrationConfigService, integrationTypeRegistry, reconciliationService)
        service.initialize()
        return service
    }

    "resolveToolsForRun returns config-less tools when no shared or user configs exist" {
        val namespaceId = UUID.randomUUID()
        val userId = UUID.randomUUID()
        val plugin = makeConfigLessPlugin("DATETIME", "GetCurrentDateTime")
        val service = buildServiceForRun(plugins = listOf(plugin))

        val tools = service.resolveToolsForRun(namespaceId, userId)

        tools shouldHaveSize 1
        tools.first().name shouldBe "GetCurrentDateTime"
    }

    "resolveToolsForRun resolves tools from namespace-shared config only (no user override)" {
        val namespaceId = UUID.randomUUID()
        val userId = UUID.randomUUID()
        val plugin = makeConfiguredPlugin("JIRA", "GetIssue")
        val shared = IntegrationConfig(metadata = EntityMetadata(), namespaceId = namespaceId, name = "jira", integrationType = "JIRA")
        val reconciled = shared
        val service = buildServiceForRun(
            plugins = listOf(plugin),
            sharedConfigs = listOf(shared),
            reconciledConfigs = mapOf("jira" to reconciled),
        )

        val tools = service.resolveToolsForRun(namespaceId, userId)

        tools shouldHaveSize 1
        tools.first().name shouldBe "GetIssue"
    }

    "resolveToolsForRun resolves tools from user-global override only (no namespace config)" {
        val namespaceId = UUID.randomUUID()
        val userId = UUID.randomUUID()
        val plugin = makeConfiguredPlugin("GITHUB", "CreatePR")
        val userGlobal = IntegrationConfig(metadata = EntityMetadata(), userId = userId, name = "github", integrationType = "GITHUB")
        val reconciled = userGlobal
        val service = buildServiceForRun(
            plugins = listOf(plugin),
            userOverrides = listOf(userGlobal),
            reconciledConfigs = mapOf("github" to reconciled),
        )

        val tools = service.resolveToolsForRun(namespaceId, userId)

        tools shouldHaveSize 1
        tools.first().name shouldBe "CreatePR"
    }

    "resolveToolsForRun 3-tier fold: user×namespace override applied" {
        val namespaceId = UUID.randomUUID()
        val userId = UUID.randomUUID()
        val plugin = makeConfiguredPlugin("JIRA", "GetIssue")
        val shared = IntegrationConfig(metadata = EntityMetadata(), namespaceId = namespaceId, name = "jira", integrationType = "JIRA")
        val userNs = IntegrationConfig(metadata = EntityMetadata(), namespaceId = namespaceId, userId = userId, name = "jira", integrationType = "JIRA")
        val reconciled = userNs
        val service = buildServiceForRun(
            plugins = listOf(plugin),
            sharedConfigs = listOf(shared),
            userOverrides = listOf(userNs),
            reconciledConfigs = mapOf("jira" to reconciled),
        )

        val tools = service.resolveToolsForRun(namespaceId, userId)

        tools shouldHaveSize 1
    }

    "resolveToolsForRun fails fast when a namespace-shared name fails reconciliation (NFR-REL-1)" {
        // A name enumerated from findByNamespaceShared MUST resolve - if reconciliation
        // throws ConfigNotFoundException for it, the run aborts rather than silently
        // producing a partial toolset (review H-8). This is the fail-closed posture.
        val namespaceId = UUID.randomUUID()
        val userId = UUID.randomUUID()
        val plugin = makeConfiguredPlugin("GITHUB", "CreatePR")
        val shared1 = IntegrationConfig(metadata = EntityMetadata(), namespaceId = namespaceId, name = "jira", integrationType = "JIRA")
        val shared2 = IntegrationConfig(metadata = EntityMetadata(), namespaceId = namespaceId, name = "github", integrationType = "GITHUB")
        val service = buildServiceForRun(
            plugins = listOf(plugin),
            sharedConfigs = listOf(shared1, shared2),
            reconciledConfigs = mapOf("github" to shared2),
            // "jira" is in sharedConfigs but not in reconciledConfigs → throws.
        )

        shouldThrow<ConfigNotFoundException> {
            service.resolveToolsForRun(namespaceId, userId)
        }
    }

    "resolveToolsForRun silently skips dormant user overrides whose name has no shared config (FR30)" {
        // Dormant override path: user persisted an override targeting a name that no longer
        // exists in any shared config. The reconciliation throws but the run continues —
        // FR30: "remains dormant without raising an error".
        val namespaceId = UUID.randomUUID()
        val userId = UUID.randomUUID()
        val plugin = makeConfiguredPlugin("GITHUB", "CreatePR")
        val shared = IntegrationConfig(metadata = EntityMetadata(), namespaceId = namespaceId, name = "github", integrationType = "GITHUB")
        // dormant: targets "ghost-name" but no shared config has that name.
        val dormantOverride = IntegrationConfig(metadata = EntityMetadata(), namespaceId = namespaceId, userId = userId, name = "ghost-name", integrationType = "JIRA")
        val service = buildServiceForRun(
            plugins = listOf(plugin),
            sharedConfigs = listOf(shared),
            userOverrides = listOf(dormantOverride),
            reconciledConfigs = mapOf("github" to shared),
            // "ghost-name" not in reconciledConfigs → throws but is dormant → skipped silently.
        )

        val tools = service.resolveToolsForRun(namespaceId, userId)

        // dormant "ghost-name" silently skipped, "github" resolved
        tools shouldHaveSize 1
        tools.first().name shouldBe "CreatePR"
    }

    "resolveToolsForRun dormant override on different namespace is filtered out (AC4)" {
        val ns1 = UUID.randomUUID()
        val ns2 = UUID.randomUUID()
        val userId = UUID.randomUUID()
        val plugin = makeConfiguredPlugin("JIRA", "GetIssue")
        // override for ns2 should NOT appear in ns1 resolution
        val overrideForNs2 = IntegrationConfig(metadata = EntityMetadata(), namespaceId = ns2, userId = userId, name = "jira", integrationType = "JIRA")
        val service = buildServiceForRun(
            plugins = listOf(plugin),
            userOverrides = listOf(overrideForNs2),
            // no reconciledConfigs needed: jira not enumerated for ns1
        )

        val tools = service.resolveToolsForRun(ns1, userId)

        // override for ns2 is filtered, no jira tools for ns1
        tools shouldHaveSize 0
    }

    "resolveToolsForRun combines config-less and reconciled config tools" {
        val namespaceId = UUID.randomUUID()
        val userId = UUID.randomUUID()
        val configLessPlugin = makeConfigLessPlugin("DATETIME", "GetCurrentDateTime")
        val configuredPlugin = makeConfiguredPlugin("JIRA", "GetIssue")
        val shared = IntegrationConfig(metadata = EntityMetadata(), namespaceId = namespaceId, name = "jira", integrationType = "JIRA")
        val service = buildServiceForRun(
            plugins = listOf(configLessPlugin, configuredPlugin),
            sharedConfigs = listOf(shared),
            reconciledConfigs = mapOf("jira" to shared),
        )

        val tools = service.resolveToolsForRun(namespaceId, userId)

        tools shouldHaveSize 2
        tools.map { it.name }.toSet() shouldBe setOf("GetCurrentDateTime", "GetIssue")
    }

})
