package io.whozoss.agentos.tool

import com.fasterxml.jackson.databind.JsonNode
import io.kotest.core.spec.style.StringSpec
import io.kotest.matchers.collections.shouldHaveSize
import io.kotest.matchers.shouldBe
import io.kotest.matchers.shouldNotBe
import io.mockk.every
import io.mockk.mockk
import io.mockk.verify
import io.whozoss.agentos.integrationConfig.IntegrationConfig
import io.whozoss.agentos.integrationConfig.IntegrationConfigService
import io.whozoss.agentos.integrationConfig.IntegrationTypeRegistry
import io.whozoss.agentos.reconciliation.ConfigReconciliationService
import io.whozoss.agentos.sdk.entity.EntityMetadata
import io.whozoss.agentos.sdk.tool.StandardTool
import io.whozoss.agentos.sdk.tool.ToolPlugin
import org.pf4j.PluginDescriptor
import org.pf4j.PluginManager
import org.pf4j.PluginWrapper
import java.util.UUID

/**
 * Tests for [ToolRegistryService], focusing on the tool lifecycle contract introduced
 * by GitHub issue #733: tools must be scoped to the agent run, not shared as singletons.
 *
 * Key invariant: every call to [ToolRegistryService.resolveToolsForNamespace] must
 * produce **fresh tool instances**, regardless of whether the plugin requires configuration.
 */
class ToolRegistryServiceSpec : StringSpec({

    fun makeTool(name: String): StandardTool<Nothing> =
        object : StandardTool<Nothing> {
            override val name = name
            override val description = "Tool $name"
            override val inputSchema = """{"type":"object"}"""
            override val version = "1.0.0"
            override val paramType: Class<Nothing>? = null
            override fun execute(input: Nothing?): String = name
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
        val pluginManager = mockk<PluginManager>(relaxed = true)
        every { pluginManager.getExtensions(ToolPlugin::class.java) } returns plugins
        // whichPlugin returns null for all (no plugin wrapper needed for unit tests)
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

    // -------------------------------------------------------------------------
    // Core lifecycle contract: fresh instances per run
    // -------------------------------------------------------------------------

    "resolveToolsForNamespace produces distinct tool instances on each call for config-less plugins" {
        // This is the core regression test for issue #733.
        // Before the fix, config-less tools were singletons: resolveToolsForNamespace returned
        // the same instances registered at startup. After the fix, each call instantiates
        // fresh tools so no tool instance outlives its owning agent run.
        val plugin = makeConfigLessPlugin("DATETIME", "GetCurrentDateTime")
        val service = buildService(plugins = listOf(plugin))
        val namespaceId = UUID.randomUUID()

        val tools1 = service.resolveToolsForNamespace(namespaceId)
        val tools2 = service.resolveToolsForNamespace(namespaceId)

        tools1 shouldHaveSize 1
        tools2 shouldHaveSize 1
        // Different instances — not the same object reference
        tools1.first() shouldNotBe tools2.first()
    }

    "resolveToolsForNamespace produces distinct tool instances on each call for configured plugins" {
        // Configured tools were already fresh per call before the fix; this test guards
        // against any regression that would accidentally cache them.
        val namespaceId = UUID.randomUUID()
        val config = IntegrationConfig(
            metadata = EntityMetadata(),
            namespaceId = namespaceId,
            name = "JIRA_PROD",
            integrationType = "JIRA",
        )
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

    "resolveToolsForNamespace returns config-less tools when namespace has no IntegrationConfig" {
        val plugin = makeConfigLessPlugin("DATETIME", "GetCurrentDateTime", "GetCurrentDate")
        val service = buildService(plugins = listOf(plugin))
        val namespaceId = UUID.randomUUID()

        val tools = service.resolveToolsForNamespace(namespaceId)

        tools shouldHaveSize 2
        tools.map { it.name }.toSet() shouldBe setOf("GetCurrentDateTime", "GetCurrentDate")
    }

    "resolveToolsForNamespace returns configured tools matching the namespace" {
        val namespaceId = UUID.randomUUID()
        val config = IntegrationConfig(
            metadata = EntityMetadata(),
            namespaceId = namespaceId,
            name = "JIRA_PROD",
            integrationType = "JIRA",
        )
        val plugin = makeConfiguredPlugin("JIRA", "GetIssue", "SearchIssues")
        val service = buildService(plugins = listOf(plugin), configs = listOf(config))

        val tools = service.resolveToolsForNamespace(namespaceId)

        tools shouldHaveSize 2
        tools.map { it.name }.toSet() shouldBe setOf("GetIssue", "SearchIssues")
    }

    "resolveToolsForNamespace combines config-less and configured tools" {
        val namespaceId = UUID.randomUUID()
        val config = IntegrationConfig(
            metadata = EntityMetadata(),
            namespaceId = namespaceId,
            name = "JIRA_PROD",
            integrationType = "JIRA",
        )
        val configLessPlugin = makeConfigLessPlugin("DATETIME", "GetCurrentDateTime")
        val configuredPlugin = makeConfiguredPlugin("JIRA", "GetIssue")
        val service = buildService(plugins = listOf(configLessPlugin, configuredPlugin), configs = listOf(config))

        val tools = service.resolveToolsForNamespace(namespaceId)

        tools shouldHaveSize 2
        tools.map { it.name }.toSet() shouldBe setOf("GetCurrentDateTime", "GetIssue")
    }

    "resolveToolsForNamespace returns no configured tools for a namespace with no matching IntegrationConfig" {
        val namespaceId = UUID.randomUUID()
        val otherNamespaceId = UUID.randomUUID()
        val configInOtherNamespace = IntegrationConfig(
            metadata = EntityMetadata(),
            namespaceId = otherNamespaceId,
            name = "JIRA_PROD",
            integrationType = "JIRA",
        )
        val configLessPlugin = makeConfigLessPlugin("DATETIME", "GetCurrentDateTime")
        val configuredPlugin = makeConfiguredPlugin("JIRA", "GetIssue")
        val service = buildService(
            plugins = listOf(configLessPlugin, configuredPlugin),
            configs = listOf(configInOtherNamespace),
        )

        val tools = service.resolveToolsForNamespace(namespaceId)

        // Only config-less tools, no JIRA tools for this namespace
        tools shouldHaveSize 1
        tools.first().name shouldBe "GetCurrentDateTime"
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

    "resolveToolsForRun swallows ConfigNotFoundException and continues with other names (AC2)" {
        val namespaceId = UUID.randomUUID()
        val userId = UUID.randomUUID()
        val plugin = makeConfiguredPlugin("GITHUB", "CreatePR")
        val shared1 = IntegrationConfig(metadata = EntityMetadata(), namespaceId = namespaceId, name = "jira", integrationType = "JIRA")
        val shared2 = IntegrationConfig(metadata = EntityMetadata(), namespaceId = namespaceId, name = "github", integrationType = "GITHUB")
        val service = buildServiceForRun(
            plugins = listOf(plugin),
            sharedConfigs = listOf(shared1, shared2),
            reconciledConfigs = mapOf("github" to shared2),
            // "jira" not in reconciledConfigs → ConfigNotFoundException
        )

        val tools = service.resolveToolsForRun(namespaceId, userId)

        // "jira" swallowed, "github" resolved
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
