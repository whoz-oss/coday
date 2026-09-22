package io.whozoss.agentos.tool

import com.fasterxml.jackson.databind.JsonNode
import io.kotest.core.spec.style.StringSpec
import io.kotest.matchers.collections.shouldBeEmpty
import io.kotest.matchers.collections.shouldHaveSize
import io.kotest.matchers.shouldBe
import io.kotest.matchers.shouldNotBe
import io.mockk.every
import io.mockk.mockk
import io.whozoss.agentos.exchange.ExchangeToolsConfigProperties
import io.whozoss.agentos.integrationConfig.IntegrationConfig
import io.whozoss.agentos.integrationConfig.IntegrationTypeRegistry
import io.whozoss.agentos.sdk.entity.EntityMetadata
import io.whozoss.agentos.sdk.tool.StandardTool
import io.whozoss.agentos.sdk.tool.ToolContext
import io.whozoss.agentos.sdk.tool.ToolExecutionResult
import io.whozoss.agentos.sdk.tool.ToolPlugin
import org.pf4j.PluginManager
import java.util.UUID

class ToolResolverServiceSpec :
    StringSpec({

        // -------------------------------------------------------------------------
        // Shared fixtures
        // -------------------------------------------------------------------------

        fun makeTool(
            name: String,
            integrationName: String? = null,
        ): StandardTool<Nothing> =
            object : StandardTool<Nothing> {
                override val name = (integrationName?.let { "${it}__" } ?: "") + name
                override val description = (integrationName?.let { "${it}__" } ?: "") + name
                override val inputSchema = """{"type":"object"}"""
                override val version = "1.0.0"
                override val paramType: Class<Nothing>? = null

                override suspend fun execute(
                    input: Nothing?,
                    context: ToolContext,
                ): ToolExecutionResult = ToolExecutionResult.success(name)
            }

        fun makePlugin(
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
                ): List<StandardTool<*>> = toolNames.map { makeTool(it, configName) }
            }

        fun integrationConfig(
            namespaceId: UUID? = null,
            userId: UUID? = null,
            name: String,
            integrationType: String,
            parameters: JsonNode? = null,
        ) = IntegrationConfig(
            metadata = EntityMetadata(),
            namespaceId = namespaceId,
            userId = userId,
            name = name,
            integrationType = integrationType,
            parameters = parameters,
        )

        fun ctx(
            namespaceId: UUID,
            userId: UUID? = null,
        ) = ToolContext(
            namespaceId = namespaceId,
            userId = userId,
            userExternalId = null,
            caseEvents = emptyList(),
            agentName = null,
        )

        fun initRegistry(plugins: List<ToolPlugin>): ToolRegistryService {
            val pluginManager = mockk<PluginManager>(relaxed = true)
            every { pluginManager.getExtensions(ToolPlugin::class.java) } returns plugins
            every { pluginManager.whichPlugin(any()) } returns null
            val registry =
                ToolRegistryService(
                    pluginManager,
                    mockk<IntegrationTypeRegistry>(relaxed = true),
                    ExchangeToolsConfigProperties(),
                )
            registry.initialize()
            return registry
        }

        fun buildService(plugins: List<ToolPlugin> = emptyList()): ToolResolverService = ToolResolverService(initRegistry(plugins))

        // -------------------------------------------------------------------------
        // Core lifecycle contract
        // -------------------------------------------------------------------------

        "resolveToolsForRun produces distinct tool instances on each call" {
            val nsId = UUID.randomUUID()
            val plugin = makePlugin("DATETIME", "GetCurrentDateTime")
            val config = integrationConfig(namespaceId = nsId, name = "MY_DATETIME", integrationType = "DATETIME")
            val service = buildService(plugins = listOf(plugin))
            val agentIntegrations = mapOf("MY_DATETIME" to null)

            val tools1 =
                service.resolveToolsForRun(
                    agentIntegrations = agentIntegrations,
                    context = ctx(nsId),
                    allIntegrationConfigs = listOf(config),
                )
            val tools2 =
                service.resolveToolsForRun(
                    agentIntegrations = agentIntegrations,
                    context = ctx(nsId),
                    allIntegrationConfigs = listOf(config),
                )

            tools1 shouldHaveSize 1
            tools2 shouldHaveSize 1
            tools1.first() shouldNotBe tools2.first()
        }

        // -------------------------------------------------------------------------
        // Correctness: right tools are returned
        // -------------------------------------------------------------------------

        "resolveToolsForRun returns no tools when allIntegrationConfigs is empty" {
            val service = buildService(plugins = listOf(makePlugin("DATETIME", "GetCurrentDateTime")))

            service.resolveToolsForRun(context = ctx(UUID.randomUUID()), allIntegrationConfigs = emptyList()).shouldBeEmpty()
        }

        "resolveToolsForRun returns all tools from a matching config" {
            val nsId = UUID.randomUUID()
            val config = integrationConfig(namespaceId = nsId, name = "MY_DATETIME", integrationType = "DATETIME")
            val service = buildService(plugins = listOf(makePlugin("DATETIME", "GetCurrentDateTime", "GetCurrentDate")))

            val tools =
                service.resolveToolsForRun(
                    agentIntegrations = mapOf("MY_DATETIME" to null),
                    context = ctx(nsId),
                    allIntegrationConfigs = listOf(config),
                )

            tools shouldHaveSize 2
            tools.map { it.name }.toSet() shouldBe setOf("MY_DATETIME__GetCurrentDateTime", "MY_DATETIME__GetCurrentDate")
        }

        "resolveToolsForRun combines tools from multiple IntegrationConfigs" {
            val nsId = UUID.randomUUID()
            val service =
                buildService(
                    plugins = listOf(makePlugin("DATETIME", "GetCurrentDateTime"), makePlugin("JIRA", "GetIssue")),
                )
            val configs =
                listOf(
                    integrationConfig(namespaceId = nsId, name = "MY_DATETIME", integrationType = "DATETIME"),
                    integrationConfig(namespaceId = nsId, name = "JIRA_PROD", integrationType = "JIRA"),
                )

            val tools =
                service.resolveToolsForRun(
                    agentIntegrations = mapOf("MY_DATETIME" to null, "JIRA_PROD" to null),
                    context = ctx(nsId),
                    allIntegrationConfigs = configs,
                )

            tools shouldHaveSize 2
            tools.map { it.name }.toSet() shouldBe setOf("MY_DATETIME__GetCurrentDateTime", "JIRA_PROD__GetIssue")
        }

        "resolveToolsForRun ignores configs not listed in agentIntegrations" {
            val nsId = UUID.randomUUID()
            val service =
                buildService(
                    plugins = listOf(makePlugin("JIRA", "GetIssue"), makePlugin("DATETIME", "GetCurrentDateTime")),
                )
            val configs =
                listOf(
                    integrationConfig(namespaceId = nsId, name = "JIRA_PROD", integrationType = "JIRA"),
                    integrationConfig(namespaceId = nsId, name = "MY_DATETIME", integrationType = "DATETIME"),
                )

            val tools =
                service.resolveToolsForRun(
                    agentIntegrations = mapOf("JIRA_PROD" to null),
                    context = ctx(nsId),
                    allIntegrationConfigs = configs,
                )

            tools shouldHaveSize 1
            tools.first().name shouldBe "JIRA_PROD__GetIssue"
        }

        "resolveToolsForRun returns 4 tools from two configs of the same plugin type" {
            // Two IntegrationConfigs (JIRA_PROD, JIRA_STAGING) share the same integrationType.
            // The plugin prefixes tool names with configName, so all 4 names are distinct.
            val nsId = UUID.randomUUID()
            val jiraPlugin = makePlugin("JIRA", "GetIssue", "SearchIssues")
            val service = buildService(plugins = listOf(jiraPlugin))
            val configs =
                listOf(
                    integrationConfig(namespaceId = nsId, name = "JIRA_PROD", integrationType = "JIRA"),
                    integrationConfig(namespaceId = nsId, name = "JIRA_STAGING", integrationType = "JIRA"),
                )

            val tools =
                service.resolveToolsForRun(
                    agentIntegrations = mapOf("JIRA_PROD" to null, "JIRA_STAGING" to null),
                    context = ctx(nsId),
                    allIntegrationConfigs = configs,
                )

            tools shouldHaveSize 4
            tools.map { it.name }.toSet() shouldBe
                setOf("JIRA_PROD__GetIssue", "JIRA_PROD__SearchIssues", "JIRA_STAGING__GetIssue", "JIRA_STAGING__SearchIssues")
        }

        // -------------------------------------------------------------------------
        // agentIntegrations filter
        // -------------------------------------------------------------------------

        "resolveToolsForRun with agentIntegrations null returns no tools" {
            // null means the agent declared no integrations at all — no tools are loaded.
            val nsId = UUID.randomUUID()
            val service =
                buildService(
                    plugins = listOf(makePlugin("JIRA", "GetIssue"), makePlugin("DATETIME", "GetCurrentDateTime")),
                )
            val configs =
                listOf(
                    integrationConfig(namespaceId = nsId, name = "JIRA_PROD", integrationType = "JIRA"),
                    integrationConfig(namespaceId = nsId, name = "MY_DATETIME", integrationType = "DATETIME"),
                )

            service.resolveToolsForRun(agentIntegrations = null, context = ctx(nsId), allIntegrationConfigs = configs).shouldBeEmpty()
        }

        "resolveToolsForRun with explicit integrations map returns all matching tools" {
            val nsId = UUID.randomUUID()
            val service =
                buildService(
                    plugins = listOf(makePlugin("JIRA", "GetIssue"), makePlugin("DATETIME", "GetCurrentDateTime")),
                )
            val configs =
                listOf(
                    integrationConfig(namespaceId = nsId, name = "JIRA_PROD", integrationType = "JIRA"),
                    integrationConfig(namespaceId = nsId, name = "MY_DATETIME", integrationType = "DATETIME"),
                )

            service.resolveToolsForRun(
                agentIntegrations = mapOf("JIRA_PROD" to null, "MY_DATETIME" to null),
                context = ctx(nsId),
                allIntegrationConfigs = configs,
            ) shouldHaveSize 2
        }

        "resolveToolsForRun with non-null allowed list filters tools within an integration" {
            val nsId = UUID.randomUUID()
            val service = buildService(plugins = listOf(makePlugin("JIRA", "GetIssue", "SearchIssues", "CreateIssue")))
            val config = integrationConfig(namespaceId = nsId, name = "JIRA_PROD", integrationType = "JIRA")

            val tools =
                service.resolveToolsForRun(
                    agentIntegrations = mapOf("JIRA_PROD" to listOf("GetIssue", "SearchIssues")),
                    context = ctx(nsId),
                    allIntegrationConfigs = listOf(config),
                )

            tools shouldHaveSize 2
            tools.map { it.name }.toSet() shouldBe setOf("JIRA_PROD__GetIssue", "JIRA_PROD__SearchIssues")
        }

        "resolveToolsForRun with null allowed list returns all tools from that integration" {
            val nsId = UUID.randomUUID()
            val service = buildService(plugins = listOf(makePlugin("JIRA", "GetIssue", "SearchIssues", "CreateIssue")))
            val config = integrationConfig(namespaceId = nsId, name = "JIRA_PROD", integrationType = "JIRA")

            service.resolveToolsForRun(
                agentIntegrations = mapOf("JIRA_PROD" to null),
                context = ctx(nsId),
                allIntegrationConfigs = listOf(config),
            ) shouldHaveSize 3
        }

        // -------------------------------------------------------------------------
        // isToolAllowed
        // -------------------------------------------------------------------------

        "isToolAllowed returns true when allowedNames is null" {
            buildService().isToolAllowed("ReadFile", "FILES", null) shouldBe true
        }

        "isToolAllowed returns true for exact name match" {
            buildService().isToolAllowed("ReadFile", "FILES", listOf("ReadFile", "ListFiles")) shouldBe true
        }

        "isToolAllowed returns false when name not in allowed list" {
            buildService().isToolAllowed("EditFile", "FILES", listOf("ReadFile", "ListFiles")) shouldBe false
        }

        "isToolAllowed matches prefixed tool name via KEY__suffix convention" {
            buildService().isToolAllowed("JIRA_PROD__GetIssue", "JIRA_PROD", listOf("GetIssue")) shouldBe true
        }

        "isToolAllowed does not match wrong prefix" {
            buildService().isToolAllowed("JIRA_STAGING__GetIssue", "JIRA_PROD", listOf("GetIssue")) shouldBe false
        }

        // -------------------------------------------------------------------------
        // dedupToolsByName — ordering guarantee (prompt-cache stability)
        // -------------------------------------------------------------------------

        "dedupToolsByName returns tools in alphabetical order by name" {
            // Tools intentionally given in reverse-alphabetical order to detect sort.
            val tools =
                listOf(
                    makeTool("Zebra"),
                    makeTool("Mango"),
                    makeTool("Apple"),
                )

            val result = buildService().dedupToolsByName(tools)

            result.map { it.name } shouldBe listOf("Apple", "Mango", "Zebra")
        }

        "dedupToolsByName produces the same ordered list regardless of the input order" {
            // Core regression guard: two calls with the same logical tool set but different
            // input orderings must produce identical output. This is the property that
            // prevents OpenAI prompt-cache invalidation when Neo4j changes its return order.
            val service = buildService()
            val toolsAbc =
                listOf(
                    makeTool("Alpha"),
                    makeTool("Beta"),
                    makeTool("Gamma"),
                )
            val toolsCba =
                listOf(
                    makeTool("Gamma"),
                    makeTool("Beta"),
                    makeTool("Alpha"),
                )

            val resultAbc = service.dedupToolsByName(toolsAbc)
            val resultCba = service.dedupToolsByName(toolsCba)

            resultAbc.map { it.name } shouldBe resultCba.map { it.name }
        }

        "dedupToolsByName keeps the first-encountered tool when names collide" {
            // Non-regression: the first tool in the input list is the one kept, not
            // the first alphabetically. Sorting happens AFTER selection, so this is
            // unaffected by the sort.
            val keeper = makeTool("tool").also { _ -> }   // will be first in input
            // Build a second tool with the same name but a different description
            // so we can distinguish them.
            val duplicate =
                object : io.whozoss.agentos.sdk.tool.StandardTool<Nothing> {
                    override val name = "tool"
                    override val description = "DUPLICATE — must be dropped"
                    override val inputSchema = """{"type":"object"}"""
                    override val version = "1.0.0"
                    override val paramType: Class<Nothing>? = null

                    override suspend fun execute(
                        input: Nothing?,
                        context: ToolContext,
                    ): io.whozoss.agentos.sdk.tool.ToolExecutionResult =
                        io.whozoss.agentos.sdk.tool.ToolExecutionResult.success(name)
                }

            val result = buildService().dedupToolsByName(listOf(keeper, duplicate))

            result shouldHaveSize 1
            // The kept tool must be the first one from the original list, not the duplicate.
            result.first().description shouldBe keeper.description
            result.first().description shouldNotBe "DUPLICATE — must be dropped"
        }

        "dedupToolsByName on an empty list returns an empty list" {
            buildService().dedupToolsByName(emptyList()).shouldBeEmpty()
        }

        "dedupToolsByName on a single-element list returns that element" {
            val tool = makeTool("OnlyTool")

            val result = buildService().dedupToolsByName(listOf(tool))

            result shouldHaveSize 1
            result.first().name shouldBe "OnlyTool"
        }

        "dedupToolsByName uses binary (locale-independent) ordering" {
            // Uppercase ASCII letters sort before lowercase in binary order (A=65, Z=90, a=97).
            // A locale-sensitive sort (e.g. Locale.FRENCH) would treat 'A' and 'a' as equal
            // and produce a different, locale-dependent order. We verify the binary contract.
            val tools =
                listOf(
                    makeTool("zebra"),   // lowercase z = 122
                    makeTool("Apple"),   // uppercase A = 65
                    makeTool("Banana"),  // uppercase B = 66
                )

            val result = buildService().dedupToolsByName(tools)

            // Binary order: 'A'(65) < 'B'(66) < 'z'(122)
            result.map { it.name } shouldBe listOf("Apple", "Banana", "zebra")
        }

        "resolveToolsForRun returns tools in alphabetical order" {
            // End-to-end: the order guarantee must hold even when tools come from the
            // integration-config resolution path, not just from a direct dedupToolsByName call.
            val nsId = UUID.randomUUID()
            val jiraPlugin = makePlugin("JIRA", "SearchIssues", "GetIssue")  // reversed alphabetical
            val service = buildService(plugins = listOf(jiraPlugin))
            val config = integrationConfig(namespaceId = nsId, name = "JIRA_PROD", integrationType = "JIRA")

            val tools =
                service.resolveToolsForRun(
                    agentIntegrations = mapOf("JIRA_PROD" to null),
                    context = ctx(nsId),
                    allIntegrationConfigs = listOf(config),
                )

            // Alphabetical: JIRA_PROD__GetIssue < JIRA_PROD__SearchIssues
            tools.map { it.name } shouldBe listOf("JIRA_PROD__GetIssue", "JIRA_PROD__SearchIssues")
        }

        // -------------------------------------------------------------------------
        // ToolContext forwarding
        // -------------------------------------------------------------------------

        "resolveToolsForRun passes the full ToolContext to plugins" {
            val nsId = UUID.randomUUID()
            var capturedContext: ToolContext? = null
            val capturingPlugin =
                object : ToolPlugin {
                    override val integrationType = "REDIRECT"
                    override val configSchema: JsonNode? = null

                    override fun provideTools(
                        config: JsonNode?,
                        configName: String?,
                        context: ToolContext?,
                    ): List<StandardTool<*>> {
                        capturedContext = context
                        return if (context?.namespaceId != null) listOf(makeTool("redirect")) else emptyList()
                    }
                }
            val service = buildService(plugins = listOf(capturingPlugin))
            val config = integrationConfig(namespaceId = nsId, name = "REDIRECT_all", integrationType = "REDIRECT")

            val tools =
                service.resolveToolsForRun(
                    agentIntegrations = mapOf("REDIRECT_all" to null),
                    context = ctx(nsId),
                    allIntegrationConfigs = listOf(config),
                )

            capturedContext shouldNotBe null
            capturedContext!!.namespaceId shouldBe nsId
            tools shouldHaveSize 1
        }
    })
