package io.whozoss.agentos.tool

import com.fasterxml.jackson.databind.JsonNode
import com.fasterxml.jackson.databind.ObjectMapper
import io.kotest.core.spec.style.StringSpec
import io.kotest.matchers.collections.shouldBeEmpty
import io.kotest.matchers.collections.shouldHaveSize
import io.kotest.matchers.shouldBe
import io.kotest.matchers.shouldNotBe
import io.mockk.every
import io.mockk.mockk
import io.whozoss.agentos.integrationConfig.IntegrationConfig
import io.whozoss.agentos.integrationConfig.IntegrationConfigMergeStrategy
import io.whozoss.agentos.integrationConfig.IntegrationConfigService
import io.whozoss.agentos.integrationConfig.IntegrationTypeRegistry
import io.whozoss.agentos.sdk.entity.EntityMetadata
import io.whozoss.agentos.sdk.tool.StandardTool
import io.whozoss.agentos.sdk.tool.ToolContext
import io.whozoss.agentos.sdk.tool.ToolExecutionResult
import io.whozoss.agentos.sdk.tool.ToolPlugin
import org.pf4j.PluginManager
import java.util.UUID

/**
 * Calls [ToolResolverService.resolveConfigs] directly with an explicit config list,
 * bypassing the repository. Used by merge-focused tests that don't care about plugins.
 */
private fun ToolResolverService.resolveConfigsFrom(
    configs: List<IntegrationConfig>,
    namespaceId: UUID? = null,
    userId: UUID? = null,
): List<IntegrationConfig> =
    resolveConfigs(
        // Pass all config names so the mock returns them — mirrors an agent that declares all of them.
        integrationConfigNames = configs.map { it.name },
        context =
            ToolContext(
                namespaceId = namespaceId ?: UUID.randomUUID(),
                userId = userId,
                userExternalId = null,
                caseEvents = emptyList(),
                agentName = null,
            ),
    )

class ToolResolverServiceSpec :
    StringSpec({
        val mergeStrategy = IntegrationConfigMergeStrategy()
        val mapper = ObjectMapper()

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

        fun json(vararg pairs: Pair<String, String>): JsonNode = mapper.createObjectNode().apply { pairs.forEach { (k, v) -> put(k, v) } }

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

        /**
         * Scope-aware mock: returns the subset of [configs] matching the requested names,
         * namespaceId, and userId. An empty [names] list returns nothing (mirrors the real
         * repository behaviour: no names requested = no configs returned).
         */
        fun mockConfigService(configs: List<IntegrationConfig>): IntegrationConfigService =
            mockk<IntegrationConfigService>(relaxed = true).also { svc ->
                every {
                    svc.findAllByNamesForNamespaceIdAndUserId(
                        names = any(),
                        namespaceId = any(),
                        userId = any(),
                    )
                } answers {
                    val names = firstArg<List<String>>()
                    val namespaceId = secondArg<UUID?>()
                    val userId = thirdArg<UUID?>()
                    configs.filter { cfg ->
                        val scopeMatch =
                            (cfg.namespaceId == null && cfg.userId == null) ||
                                (cfg.namespaceId == namespaceId && cfg.userId == null) ||
                                (cfg.namespaceId == null && cfg.userId == userId) ||
                                (cfg.namespaceId == namespaceId && cfg.userId == userId)
                        val nameMatch = cfg.name in names
                        scopeMatch && nameMatch
                    }
                }
            }

        fun initRegistry(plugins: List<ToolPlugin>): ToolRegistryService {
            val pluginManager = mockk<PluginManager>(relaxed = true)
            every { pluginManager.getExtensions(ToolPlugin::class.java) } returns plugins
            every { pluginManager.whichPlugin(any()) } returns null
            val registry = ToolRegistryService(pluginManager, mockk<IntegrationTypeRegistry>(relaxed = true))
            registry.initialize()
            return registry
        }

        fun buildService(
            plugins: List<ToolPlugin> = emptyList(),
            configs: List<IntegrationConfig> = emptyList(),
        ): ToolResolverService = ToolResolverService(initRegistry(plugins), mockConfigService(configs), mergeStrategy)

        /** Minimal service for resolveConfigs tests — no plugins needed. */
        fun buildMergeService(configs: List<IntegrationConfig>): ToolResolverService =
            ToolResolverService(initRegistry(emptyList()), mockConfigService(configs), mergeStrategy)

        // -------------------------------------------------------------------------
        // Core lifecycle contract
        // -------------------------------------------------------------------------

        "resolveToolsForRun produces distinct tool instances on each call" {
            val nsId = UUID.randomUUID()
            val plugin = makePlugin("DATETIME", "GetCurrentDateTime")
            val config = integrationConfig(namespaceId = nsId, name = "MY_DATETIME", integrationType = "DATETIME")
            val service = buildService(plugins = listOf(plugin), configs = listOf(config))
            val agentIntegrations = mapOf("MY_DATETIME" to null)

            val tools1 = service.resolveToolsForRun(agentIntegrations = agentIntegrations, context = ctx(nsId))
            val tools2 = service.resolveToolsForRun(agentIntegrations = agentIntegrations, context = ctx(nsId))

            tools1 shouldHaveSize 1
            tools2 shouldHaveSize 1
            tools1.first() shouldNotBe tools2.first()
        }

        // -------------------------------------------------------------------------
        // Correctness: right tools are returned
        // -------------------------------------------------------------------------

        "resolveToolsForRun returns no tools when no IntegrationConfig exists for the namespace" {
            val service = buildService(plugins = listOf(makePlugin("DATETIME", "GetCurrentDateTime")))

            service.resolveToolsForRun(context = ctx(UUID.randomUUID())).shouldBeEmpty()
        }

        "resolveToolsForRun returns all tools from a matching namespace config" {
            val nsId = UUID.randomUUID()
            val config = integrationConfig(namespaceId = nsId, name = "MY_DATETIME", integrationType = "DATETIME")
            val service =
                buildService(plugins = listOf(makePlugin("DATETIME", "GetCurrentDateTime", "GetCurrentDate")), configs = listOf(config))

            val tools = service.resolveToolsForRun(agentIntegrations = mapOf("MY_DATETIME" to null), context = ctx(nsId))

            tools shouldHaveSize 2
            tools.map { it.name }.toSet() shouldBe setOf("MY_DATETIME__GetCurrentDateTime", "MY_DATETIME__GetCurrentDate")
        }

        "resolveToolsForRun combines tools from multiple IntegrationConfigs" {
            val nsId = UUID.randomUUID()
            val service =
                buildService(
                    plugins = listOf(makePlugin("DATETIME", "GetCurrentDateTime"), makePlugin("JIRA", "GetIssue")),
                    configs =
                        listOf(
                            integrationConfig(namespaceId = nsId, name = "MY_DATETIME", integrationType = "DATETIME"),
                            integrationConfig(namespaceId = nsId, name = "JIRA_PROD", integrationType = "JIRA"),
                        ),
                )

            val tools =
                service.resolveToolsForRun(
                    agentIntegrations = mapOf("MY_DATETIME" to null, "JIRA_PROD" to null),
                    context = ctx(nsId),
                )

            tools shouldHaveSize 2
            tools.map { it.name }.toSet() shouldBe setOf("MY_DATETIME__GetCurrentDateTime", "JIRA_PROD__GetIssue")
        }

        "resolveToolsForRun returns no tools when config belongs to a different namespace" {
            val config = integrationConfig(namespaceId = UUID.randomUUID(), name = "JIRA_PROD", integrationType = "JIRA")
            val service = buildService(plugins = listOf(makePlugin("JIRA", "GetIssue")), configs = listOf(config))

            service
                .resolveToolsForRun(
                    agentIntegrations = mapOf("JIRA_PROD" to null),
                    context = ctx(UUID.randomUUID()),
                ).shouldBeEmpty()
        }

        "resolveToolsForRun does not de-duplicates tools with same name from two differently named configs of the same plugin" {
            // Two IntegrationConfigs (JIRA_PROD, JIRA_STAGING) share the same integrationType.
            // The plugin returns tools with fixed names (GetIssue) regardless of configName,
            // producing a name collision across the two config instances.
            val nsId = UUID.randomUUID()
            val jiraPlugin = makePlugin("JIRA", "GetIssue", "SearchIssues")
            val service =
                buildService(
                    plugins = listOf(jiraPlugin),
                    configs =
                        listOf(
                            integrationConfig(namespaceId = nsId, name = "JIRA_PROD", integrationType = "JIRA"),
                            integrationConfig(namespaceId = nsId, name = "JIRA_STAGING", integrationType = "JIRA"),
                        ),
                )

            // 2 tools × 2 configs = 4 raw tools, de-duplicated to 2 by name
            val tools =
                service.resolveToolsForRun(
                    agentIntegrations = mapOf("JIRA_PROD" to null, "JIRA_STAGING" to null),
                    context = ctx(nsId),
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
                    configs =
                        listOf(
                            integrationConfig(namespaceId = nsId, name = "JIRA_PROD", integrationType = "JIRA"),
                            integrationConfig(namespaceId = nsId, name = "MY_DATETIME", integrationType = "DATETIME"),
                        ),
                )

            service.resolveToolsForRun(agentIntegrations = null, context = ctx(nsId)).shouldBeEmpty()
        }

        "resolveToolsForRun with explicit integrations map returns all matching tools" {
            // An agent that declares both integrations (with null allowed list = all tools) gets all tools.
            val nsId = UUID.randomUUID()
            val service =
                buildService(
                    plugins = listOf(makePlugin("JIRA", "GetIssue"), makePlugin("DATETIME", "GetCurrentDateTime")),
                    configs =
                        listOf(
                            integrationConfig(namespaceId = nsId, name = "JIRA_PROD", integrationType = "JIRA"),
                            integrationConfig(namespaceId = nsId, name = "MY_DATETIME", integrationType = "DATETIME"),
                        ),
                )

            service.resolveToolsForRun(
                agentIntegrations = mapOf("JIRA_PROD" to null, "MY_DATETIME" to null),
                context = ctx(nsId),
            ) shouldHaveSize 2
        }

        "resolveToolsForRun with agentIntegrations filters by IntegrationConfig name" {
            val nsId = UUID.randomUUID()
            val service =
                buildService(
                    plugins = listOf(makePlugin("JIRA", "GetIssue"), makePlugin("DATETIME", "GetCurrentDateTime")),
                    configs =
                        listOf(
                            integrationConfig(namespaceId = nsId, name = "JIRA_PROD", integrationType = "JIRA"),
                            integrationConfig(namespaceId = nsId, name = "MY_DATETIME", integrationType = "DATETIME"),
                        ),
                )

            val tools = service.resolveToolsForRun(agentIntegrations = mapOf("JIRA_PROD" to null), context = ctx(nsId))

            tools shouldHaveSize 1
            tools.first().name shouldBe "JIRA_PROD__GetIssue"
        }

        "resolveToolsForRun with non-null allowed list filters tools within an integration" {
            val nsId = UUID.randomUUID()
            val service =
                buildService(
                    plugins = listOf(makePlugin("JIRA", "GetIssue", "SearchIssues", "CreateIssue")),
                    configs = listOf(integrationConfig(namespaceId = nsId, name = "JIRA_PROD", integrationType = "JIRA")),
                )

            val tools =
                service.resolveToolsForRun(
                    agentIntegrations = mapOf("JIRA_PROD" to listOf("GetIssue", "SearchIssues")),
                    context = ctx(nsId),
                )

            tools shouldHaveSize 2
            tools.map { it.name }.toSet() shouldBe setOf("JIRA_PROD__GetIssue", "JIRA_PROD__SearchIssues")
        }

        "resolveToolsForRun with null allowed list returns all tools from that integration" {
            val nsId = UUID.randomUUID()
            val service =
                buildService(
                    plugins = listOf(makePlugin("JIRA", "GetIssue", "SearchIssues", "CreateIssue")),
                    configs = listOf(integrationConfig(namespaceId = nsId, name = "JIRA_PROD", integrationType = "JIRA")),
                )

            service.resolveToolsForRun(
                agentIntegrations = mapOf("JIRA_PROD" to null),
                context = ctx(nsId),
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
        // resolveConfigs: merge order and parameter propagation
        // -------------------------------------------------------------------------

        "resolveConfigs: single config is returned unchanged" {
            val nsId = UUID.randomUUID()
            val config =
                integrationConfig(
                    namespaceId = nsId,
                    name = "jira",
                    integrationType = "JIRA",
                    parameters = json("host" to "h"),
                )

            val result = buildMergeService(listOf(config)).resolveConfigsFrom(listOf(config), nsId)

            result shouldHaveSize 1
            result[0].parameters!!.get("host").asText() shouldBe "h"
        }

        "resolveConfigs: fold respects priority order regardless of repository return order" {
            // Bug guard: the old code used configs.first() as fold initial before sorting,
            // so a higher-priority config first in the list would become the base.
            // Priority: platform(0) < user-global(1) < namespace-shared(2) < user×namespace(3)
            // namespace-shared overrides user-global by design (namespace admin governs).
            val nsId = UUID.randomUUID()
            val userId = UUID.randomUUID()
            // namespace-shared (priority 2) listed AFTER user-global (priority 1) intentionally
            val userGlobal =
                integrationConfig(
                    userId = userId,
                    name = "jira",
                    integrationType = "JIRA",
                    parameters = json("host" to "user-host", "token" to "user-token"),
                )
            val shared =
                integrationConfig(
                    namespaceId = nsId,
                    name = "jira",
                    integrationType = "JIRA",
                    parameters = json("token" to "shared-token"),
                )

            val result =
                buildMergeService(listOf(shared, userGlobal))
                    .resolveConfigsFrom(listOf(shared, userGlobal), nsId, userId)

            result shouldHaveSize 1
            result[0].parameters!!.get("host").asText() shouldBe "user-host" // from user-global (base), not overridden
            result[0].parameters!!.get("token").asText() shouldBe "shared-token" // namespace-shared overrides user-global
        }

        "resolveConfigs: user-namespace (priority 3) wins over namespace-shared (priority 2)" {
            val nsId = UUID.randomUUID()
            val userId = UUID.randomUUID()
            val shared =
                integrationConfig(
                    namespaceId = nsId,
                    name = "jira",
                    integrationType = "JIRA",
                    parameters = json("token" to "shared-token", "host" to "shared-host"),
                )
            val userNs =
                integrationConfig(
                    namespaceId = nsId,
                    userId = userId,
                    name = "jira",
                    integrationType = "JIRA",
                    parameters = json("token" to "ns-token"),
                )

            val result =
                buildMergeService(listOf(userNs, shared))
                    .resolveConfigsFrom(listOf(userNs, shared), nsId, userId)

            result shouldHaveSize 1
            result[0].parameters!!.get("token").asText() shouldBe "ns-token" // user×namespace overrides shared
            result[0].parameters!!.get("host").asText() shouldBe "shared-host" // inherited from shared
        }

        "resolveConfigs: full 4-layer merge — platform < user-global < shared < user-namespace" {
            val nsId = UUID.randomUUID()
            val userId = UUID.randomUUID()
            val platform =
                integrationConfig(
                    name = "jira",
                    integrationType = "JIRA",
                    parameters = json("host" to "platform-host", "port" to "443", "org" to "platform-org", "token" to "platform-token"),
                )
            val userGlobal =
                integrationConfig(
                    userId = userId,
                    name = "jira",
                    integrationType = "JIRA",
                    parameters = json("org" to "user-org", "token" to "user-token"),
                )
            val shared =
                integrationConfig(
                    namespaceId = nsId,
                    name = "jira",
                    integrationType = "JIRA",
                    parameters = json("org" to "ns-org", "token" to "shared-token"),
                )
            val userNs =
                integrationConfig(
                    namespaceId = nsId,
                    userId = userId,
                    name = "jira",
                    integrationType = "JIRA",
                    parameters = json("token" to "final-token"),
                )
            val configs = listOf(userNs, userGlobal, platform, shared) // deliberately out of order

            val result = buildMergeService(configs).resolveConfigsFrom(configs, nsId, userId)

            result shouldHaveSize 1
            result[0].parameters!!.get("host").asText() shouldBe "platform-host" // platform, never overridden
            result[0].parameters!!.get("port").asText() shouldBe "443" // platform, never overridden
            result[0].parameters!!.get("org").asText() shouldBe "ns-org" // shared overrides user-global
            result[0].parameters!!.get("token").asText() shouldBe "final-token" // user×namespace wins all
        }

        "resolveConfigs: null override parameters inherit base parameters" {
            val nsId = UUID.randomUUID()
            val userId = UUID.randomUUID()
            val shared =
                integrationConfig(
                    namespaceId = nsId,
                    name = "jira",
                    integrationType = "JIRA",
                    parameters = json("host" to "shared-host", "token" to "shared-token"),
                )
            val userGlobal =
                integrationConfig(
                    userId = userId,
                    name = "jira",
                    integrationType = "JIRA",
                    parameters = null,
                )

            val result =
                buildMergeService(listOf(shared, userGlobal))
                    .resolveConfigsFrom(listOf(shared, userGlobal), nsId, userId)

            result shouldHaveSize 1
            result[0].parameters!!.get("host").asText() shouldBe "shared-host"
            result[0].parameters!!.get("token").asText() shouldBe "shared-token"
        }

        "resolveConfigs: platform-only config is reachable from any namespace" {
            val platform =
                integrationConfig(
                    name = "jira",
                    integrationType = "JIRA",
                    parameters = json("host" to "platform-host"),
                )

            val result =
                buildMergeService(listOf(platform))
                    .resolveConfigsFrom(listOf(platform), namespaceId = UUID.randomUUID())

            result shouldHaveSize 1
            result[0].parameters!!.get("host").asText() shouldBe "platform-host"
        }

        "resolveConfigs: user-global and platform only when namespace has no shared config" {
            // Without a namespace-shared layer, user-global (priority 1) is the highest override.
            val userId = UUID.randomUUID()
            val otherNsId = UUID.randomUUID()
            val platform =
                integrationConfig(
                    name = "jira",
                    integrationType = "JIRA",
                    parameters = json("host" to "platform-host", "token" to "platform-token"),
                )
            val userGlobal =
                integrationConfig(
                    userId = userId,
                    name = "jira",
                    integrationType = "JIRA",
                    parameters = json("token" to "user-token"),
                )
            // shared belongs to a different namespace — excluded by scope filter
            val sharedOtherNs =
                integrationConfig(
                    namespaceId = UUID.randomUUID(),
                    name = "jira",
                    integrationType = "JIRA",
                    parameters = json("token" to "wrong-token"),
                )
            val visible = listOf(platform, userGlobal)

            val result =
                buildMergeService(visible + sharedOtherNs)
                    .resolveConfigsFrom(visible, otherNsId, userId)

            result shouldHaveSize 1
            result[0].parameters!!.get("host").asText() shouldBe "platform-host"
            result[0].parameters!!.get("token").asText() shouldBe "user-token" // user-global, no shared to override it
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
            val service =
                buildService(
                    plugins = listOf(capturingPlugin),
                    configs = listOf(integrationConfig(namespaceId = nsId, name = "REDIRECT_all", integrationType = "REDIRECT")),
                )

            val tools =
                service.resolveToolsForRun(
                    agentIntegrations = mapOf("REDIRECT_all" to null),
                    context = ctx(nsId),
                )

            capturedContext shouldNotBe null
            capturedContext!!.namespaceId shouldBe nsId
            tools shouldHaveSize 1
        }
    })
