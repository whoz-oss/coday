package io.whozoss.agentos.casePlugin

import com.fasterxml.jackson.databind.JsonNode
import com.fasterxml.jackson.module.kotlin.jacksonObjectMapper
import io.whozoss.agentos.sdk.caseEvent.CaseEvent
import io.whozoss.agentos.sdk.tool.StandardTool
import io.whozoss.agentos.sdk.tool.ToolContext
import io.whozoss.agentos.sdk.tool.ToolPlugin
import io.whozoss.agentos.util.stringList
import mu.KLogging
import java.util.UUID

/**
 * Internal Spring-managed [ToolPlugin] providing the `CASE` integration: everything an agent can do
 * with *other* cases of its namespace.
 *
 * - [ReadCaseTool] (`ReadCase`): reads the transcript of another case.
 * - [FreeCaseLauncherTool] (`FreeCaseLauncher`): starts a new, independent case with another agent, for the
 *   current user.
 * - [FreeCaseLauncherForUserTool] (`FreeCaseLauncherForUser`): the same, for a user named by the caller.
 *
 * Like [io.whozoss.agentos.redirect.RedirectToolPlugin], this class is NOT annotated
 * with `@Component`: it is instantiated as a `@Bean` in [CasePluginConfiguration], so that the
 * [caseEventsLoader] lambda can be wired without a circular Spring dependency.
 * [io.whozoss.agentos.tool.ToolRegistryService] collects it alongside PF4J-loaded plugins via
 * `List<ToolPlugin>` constructor injection.
 *
 * An admin creates an [io.whozoss.agentos.integrationConfig.IntegrationConfig] of type `CASE` and
 * assigns it to the relevant [io.whozoss.agentos.agentConfig.AgentConfig] via `integrations`.
 * The three tools are provided by every config. To grant only some of them, the agent names them in its
 * `integrations` map (`CASE_x: [ReadCase, FreeCaseLauncher]`); an agent that maps the config to `null` receives
 * all three.
 *
 * ## Configuration
 *
 * - `includesTechnicalEvents`: read side, shape of the transcript returned by `ReadCase`.
 * - `agents` / `deniedAgents`: launch side, shared by both launcher tools. They have no effect on `ReadCase`.
 *
 * Starting a case for another user is not a parameter: it is a tool of its own, which an agent has to be
 * granted, and the service additionally requires the calling user to be ADMIN of the namespace.
 * `agents` / `deniedAgents` bound what an agent may start. `CASE` is not among the integration types refused in
 * user scopes ([io.whozoss.agentos.integrationConfig.IntegrationsProperties.userScopeDeniedTypes]): a user overlay
 * names no endpoint or credential, and what it may widen is bounded by the service.
 *
 * @param caseEventsLoader Lambda injected by [CasePluginConfiguration] that loads the events of a
 *   target case. Returns null when the case does not exist, when its namespace does not match the
 *   caller's, or when the user may not read it (fail-closed).
 * @param caseLaunchService Starts the cases for [FreeCaseLauncherTool]. It reaches `CaseService`
 *   through a `@Lazy` proxy, so injecting it here does not close the
 *   `CaseService -> AgentService -> ToolRegistryService -> plugins` cycle.
 */
class CaseToolPlugin(
    private val caseEventsLoader: (caseId: UUID, namespaceId: UUID, userId: UUID?) -> List<CaseEvent>?,
    private val caseLaunchService: CaseLaunchService,
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
            logger.warn { "[CaseToolPlugin] No namespaceId in context, cannot provide the case tools" }
            return emptyList()
        }

        val includesTechnicalEvents =
            config
                ?.get("includesTechnicalEvents")
                ?.asBoolean(true)
                ?: true

        val userId = context.userId
        return listOf(
            ReadCaseTool(
                configName = configName,
                includesTechnicalEvents = includesTechnicalEvents,
                caseEventsLoader = { caseId, ns -> caseEventsLoader(caseId, ns, userId) },
            ),
            FreeCaseLauncherTool(
                configName = configName,
                caseLaunchService = caseLaunchService,
                allowedAgents = config.stringList("agents").ifEmpty { listOf("*") },
                deniedAgents = config.stringList("deniedAgents").toSet(),
            ),
            FreeCaseLauncherForUserTool(
                configName = configName,
                caseLaunchService = caseLaunchService,
                allowedAgents = config.stringList("agents").ifEmpty { listOf("*") },
                deniedAgents = config.stringList("deniedAgents").toSet(),
            ),
        )
    }

    companion object : KLogging() {
        const val INTEGRATION_TYPE = "CASE"

        val CONFIG_SCHEMA: JsonNode =
            jacksonObjectMapper().readTree(
                """
                {
                  "type": "object",
                  "title": "Case Plugin Configuration",
                  "description": "Lets an agent work with other cases of the namespace: read the transcript of a case (ReadCase) and start a new case with another agent, for yourself (FreeCaseLauncher) or for another user (FreeCaseLauncherForUser). The new case lives on its own: stopping the agent's case does not stop it. It runs in the background, in the same namespace, and its result is not returned to the caller. An agent can be granted only some of the three tools by naming them in its integrations.",
                  "properties": {
                    "includesTechnicalEvents": {
                      "type": "boolean",
                      "title": "Include Technical Events",
                      "description": "ReadCase: when true (default), the transcript includes all event types (tool calls, agent selections, status changes, etc.). When false, only conversational events are included (messages, questions, answers, warnings, errors).",
                      "default": true
                    },
                    "agents": {
                      "type": "array",
                      "title": "Allowed Agents",
                      "description": "FreeCaseLauncher and FreeCaseLauncherForUser: glob patterns matching the agent names this integration may start. Use \"*\" for all agents. Examples: [\"*\"], [\"Profile*\", \"Report*\"].",
                      "items": { "type": "string" },
                      "default": ["*"]
                    },
                    "deniedAgents": {
                      "type": "array",
                      "title": "Denied Agents",
                      "description": "FreeCaseLauncher and FreeCaseLauncherForUser: exact agent names (case-insensitive) that are never started, even if they match an allowed pattern.",
                      "items": { "type": "string" },
                      "uniqueItems": true
                    }
                  },
                  "additionalProperties": false
                }
                """.trimIndent(),
            )
    }
}
