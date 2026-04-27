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

        val service = ToolRegistryService(pluginManager, integrationConfigService, integrationTypeRegistry)
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
    // Agent integrations filter
    // -------------------------------------------------------------------------

    "resolveToolsForNamespace with agentIntegrations null returns all tools" {
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

        val tools = service.resolveToolsForNamespace(namespaceId, agentIntegrations = null)

        tools shouldHaveSize 2
    }

    "resolveToolsForNamespace with agentIntegrations keeps only matching config-less integration" {
        val namespaceId = UUID.randomUUID()
        val datetimePlugin = makeConfigLessPlugin("DATETIME", "GetCurrentDateTime")
        val filesPlugin = makeConfigLessPlugin("FILES", "ReadFile", "ListFiles")
        val service = buildService(plugins = listOf(datetimePlugin, filesPlugin))

        val tools = service.resolveToolsForNamespace(namespaceId, agentIntegrations = mapOf("DATETIME" to null))

        tools shouldHaveSize 1
        tools.first().name shouldBe "GetCurrentDateTime"
    }

    "resolveToolsForNamespace with agentIntegrations null list allows all tools from that integration" {
        val namespaceId = UUID.randomUUID()
        val filesPlugin = makeConfigLessPlugin("FILES", "ReadFile", "ListFiles", "SearchFiles")
        val service = buildService(plugins = listOf(filesPlugin))

        val tools = service.resolveToolsForNamespace(namespaceId, agentIntegrations = mapOf("FILES" to null))

        tools shouldHaveSize 3
    }

    "resolveToolsForNamespace with agentIntegrations non-null list filters tools by name" {
        val namespaceId = UUID.randomUUID()
        val filesPlugin = makeConfigLessPlugin("FILES", "ReadFile", "ListFiles", "SearchFiles")
        val service = buildService(plugins = listOf(filesPlugin))

        val tools = service.resolveToolsForNamespace(
            namespaceId,
            agentIntegrations = mapOf("FILES" to listOf("ReadFile", "SearchFiles")),
        )

        tools shouldHaveSize 2
        tools.map { it.name }.toSet() shouldBe setOf("ReadFile", "SearchFiles")
    }

    "resolveToolsForNamespace filters configured integration by config name" {
        val namespaceId = UUID.randomUUID()
        val config = IntegrationConfig(
            metadata = EntityMetadata(),
            namespaceId = namespaceId,
            name = "JIRA_PROD",
            integrationType = "JIRA",
        )
        val configuredPlugin = makeConfiguredPlugin("JIRA", "GetIssue", "SearchIssues")
        val datetimePlugin = makeConfigLessPlugin("DATETIME", "GetCurrentDateTime")
        val service = buildService(plugins = listOf(configuredPlugin, datetimePlugin), configs = listOf(config))

        // Agent only has JIRA_PROD, not DATETIME
        val tools = service.resolveToolsForNamespace(
            namespaceId,
            agentIntegrations = mapOf("JIRA_PROD" to null),
        )

        tools shouldHaveSize 2
        tools.map { it.name }.toSet() shouldBe setOf("GetIssue", "SearchIssues")
    }

    "resolveToolsForNamespace filters configured integration tools by allowed list" {
        val namespaceId = UUID.randomUUID()
        val config = IntegrationConfig(
            metadata = EntityMetadata(),
            namespaceId = namespaceId,
            name = "JIRA_PROD",
            integrationType = "JIRA",
        )
        val configuredPlugin = makeConfiguredPlugin("JIRA", "GetIssue", "SearchIssues", "CreateIssue")
        val service = buildService(plugins = listOf(configuredPlugin), configs = listOf(config))

        val tools = service.resolveToolsForNamespace(
            namespaceId,
            agentIntegrations = mapOf("JIRA_PROD" to listOf("GetIssue", "SearchIssues")),
        )

        tools shouldHaveSize 2
        tools.map { it.name }.toSet() shouldBe setOf("GetIssue", "SearchIssues")
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

})
