package io.whozoss.agentos.redirect

import com.fasterxml.jackson.databind.JsonNode
import com.fasterxml.jackson.module.kotlin.jacksonObjectMapper
import io.whozoss.agentos.agentConfig.AgentConfig
import io.whozoss.agentos.sdk.tool.StandardTool
import io.whozoss.agentos.sdk.tool.ToolContext
import io.whozoss.agentos.sdk.tool.ToolPlugin
import mu.KLogging
import java.util.UUID

/**
 * Internal Spring-managed [ToolPlugin] that provides the REDIRECT integration.
 *
 * Unlike PF4J plugins, this class is NOT annotated with `@Component` — it is
 * instantiated as a `@Bean` in [io.whozoss.agentos.redirect.RedirectConfiguration] so
 * that the [agentResolver] lambda can be wired without creating a circular Spring dependency.
 * [io.whozoss.agentos.tool.ToolRegistryService] collects it via the `List<ToolPlugin>`
 * constructor injection alongside any PF4J-loaded plugins.
 *
 * It participates in the same [IntegrationConfig]-based resolution pipeline:
 * an admin creates an [io.whozoss.agentos.integrationConfig.IntegrationConfig] of
 * type `REDIRECT` (e.g. named `REDIRECT_all`) and assigns it to the relevant
 * [io.whozoss.agentos.agentConfig.AgentConfig] via `integrations`.
 *
 * ## Whitelist
 *
 * The `agents` array in the config parameters is a list of glob patterns.
 * `*` matches any sequence of characters (converted to `.*` regex, case-insensitive).
 * Example values: `["*"]`, `["Github*", "Jira*"]`.
 *
 * The final list of eligible agents is computed at [provideTools] time (i.e. when the
 * tool set is built for an agent run) by intersecting the patterns with the agents
 * actually configured in the namespace via [agentResolver]. This is a snapshot: agents
 * added to the namespace after the run starts will not appear in the redirect list for
 * that run. Agents that do not exist in the namespace are silently excluded — the LLM
 * never receives a stale or inaccessible name.
 *
 * ## Authorization
 *
 * When [ToolContext.userId] is available, [agentResolver] applies the same Neo4j graph
 * rules as the /search endpoint (DEPLOYED_TO + MEMBER/ADMIN): only agents accessible
 * to the requesting user are eligible for redirection. When [userId] is null (anonymous
 * or system call), all namespace agents matching the patterns are eligible.
 *
 * @param agentResolver Lambda injected by [io.whozoss.agentos.redirect.RedirectConfiguration]
 *   to avoid a circular Spring dependency. Given a namespace UUID, an optional user UUID,
 *   and a list of glob patterns, returns the matching [AgentConfig]s accessible to that user.
 */
class RedirectToolPlugin(
    private val agentResolver: (namespaceId: UUID, userId: UUID?, patterns: List<String>) -> List<AgentConfig>,
) : ToolPlugin {
    override val integrationType: String = INTEGRATION_TYPE

    override val configSchema: JsonNode = CONFIG_SCHEMA

    override fun provideTools(
        config: JsonNode?,
        configName: String?,
        context: ToolContext?,
    ): List<StandardTool<*>> {
        val namespaceId = context?.namespaceId
        if (namespaceId == null) {
            logger.warn { "[RedirectToolPlugin] No namespaceId in context, cannot resolve eligible agents" }
            return emptyList()
        }

        val patterns = config
            ?.get("agents")
            ?.takeIf { it.isArray }
            ?.map { it.asText() }
            ?.filter { it.isNotBlank() }
            ?.takeIf { it.isNotEmpty() }
            ?: listOf("*")

        val userId = context.userId
        val eligibleAgents = agentResolver(namespaceId, userId, patterns)
            .map { RedirectTool.EligibleAgent(name = it.name, description = it.description) }

        if (eligibleAgents.isEmpty()) {
            logger.warn { "[RedirectToolPlugin] No eligible agents found for namespace $namespaceId with patterns $patterns" }
            return emptyList()
        }

        logger.info { "[RedirectToolPlugin] Resolved ${eligibleAgents.size} eligible agent(s) for namespace $namespaceId" }
        return listOf(RedirectTool(configName = configName, eligibleAgents = eligibleAgents))
    }

    companion object : KLogging() {
        const val INTEGRATION_TYPE = "REDIRECT"

        val CONFIG_SCHEMA: JsonNode = jacksonObjectMapper().readTree(
            """
            {
                "type": "object",
                "title": "Redirect Configuration",
                "description": "Allows an agent to delegate the current request to another agent.",
                "properties": {
                    "agents": {
                        "type": "array",
                        "title": "Allowed Agents",
                        "description": "Glob patterns matching agent names this integration may redirect to. Use \"*\" for all agents. Examples: [\"*\"], [\"Github*\", \"Jira*\"].",
                        "items": { "type": "string" },
                        "default": ["*"]
                    }
                },
                "additionalProperties": false
            }
            """.trimIndent()
        )
    }
}
