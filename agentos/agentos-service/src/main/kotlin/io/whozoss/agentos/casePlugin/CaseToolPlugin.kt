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
 * - [FreeCaseLauncherTool] (`FreeCaseLauncher`): starts a new, independent case with another agent.
 *
 * Like [io.whozoss.agentos.redirect.RedirectToolPlugin], this class is NOT annotated
 * with `@Component`: it is instantiated as a `@Bean` in [CasePluginConfiguration], so that the
 * [caseEventsLoader] lambda can be wired without a circular Spring dependency.
 * [io.whozoss.agentos.tool.ToolRegistryService] collects it alongside PF4J-loaded plugins via
 * `List<ToolPlugin>` constructor injection.
 *
 * An admin creates an [io.whozoss.agentos.integrationConfig.IntegrationConfig] of type `CASE` and
 * assigns it to the relevant [io.whozoss.agentos.agentConfig.AgentConfig] via `integrations`.
 * Both tools are provided by every config. To grant only one of them, the agent names it in its
 * `integrations` map (`CASE_x: [ReadCase]`); an agent that maps the config to `null` receives both.
 *
 * ## Configuration
 *
 * - `includesTechnicalEvents`: read side, shape of the transcript returned by `ReadCase`.
 * - `agents` / `deniedAgents` / `allowOtherUsers`: launch side, see [FreeCaseLauncherTool]. They have
 *   no effect on `ReadCase`.
 *
 * `allowOtherUsers` decides whether an agent may start a case under another user's identity, which is
 * why `CASE` is on the list of integration types refused in user scopes
 * ([io.whozoss.agentos.integrationConfig.IntegrationsProperties.userScopeDeniedTypes]).
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
                allowOtherUsers = config?.get("allowOtherUsers")?.asBoolean(false) ?: false,
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
                  "description": "Lets an agent work with other cases of the namespace: read the transcript of a case (ReadCase) and start a new case with another agent (FreeCaseLauncher). The new case lives on its own: stopping the agent's case does not stop it. It runs in the background, in the same namespace, and its result is not returned to the caller. An agent can be granted only one of the two tools by naming it in its integrations.",
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
                      "description": "FreeCaseLauncher: glob patterns matching the agent names this integration may start. Use \"*\" for all agents. Examples: [\"*\"], [\"Profile*\", \"Report*\"].",
                      "items": { "type": "string" },
                      "default": ["*"]
                    },
                    "deniedAgents": {
                      "type": "array",
                      "title": "Denied Agents",
                      "description": "FreeCaseLauncher: exact agent names (case-insensitive) that are never started, even if they match an allowed pattern.",
                      "items": { "type": "string" },
                      "uniqueItems": true
                    },
                    "allowOtherUsers": {
                      "type": "boolean",
                      "title": "Allow other users",
                      "description": "FreeCaseLauncher: lets the agent start the case for a user other than the one it is running for (input field userId). Even then, only an administrator of the namespace can do it, and the target user must have access to the namespace and the agent deployed.",
                      "default": false
                    }
                  },
                  "additionalProperties": false
                }
                """.trimIndent(),
            )
    }
}
