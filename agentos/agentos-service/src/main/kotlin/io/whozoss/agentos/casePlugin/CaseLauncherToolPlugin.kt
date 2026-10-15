package io.whozoss.agentos.casePlugin

import com.fasterxml.jackson.databind.JsonNode
import com.fasterxml.jackson.module.kotlin.jacksonObjectMapper
import io.whozoss.agentos.sdk.tool.StandardTool
import io.whozoss.agentos.sdk.tool.ToolContext
import io.whozoss.agentos.sdk.tool.ToolPlugin
import io.whozoss.agentos.util.stringList
import org.springframework.stereotype.Component

/**
 * Spring-managed [ToolPlugin] providing the `CASE_LAUNCHER` integration: the [CreateCaseTool].
 *
 * A separate integration type from `CASE` (which only *reads* other cases), so that granting an
 * agent the right to read transcripts never grants it the right to start work.
 *
 * `@Component` is enough, no `@Configuration` indirection: [CaseLaunchService] reaches `CaseService`
 * through a `@Lazy` proxy, so nothing here closes the
 * `CaseService -> AgentService -> ToolRegistryService -> plugins` cycle.
 *
 * Parameters: `agents` (glob patterns, default `["*"]`) and `deniedAgents` (exact names) bound the
 * agents the tool may start; `allowOtherUsers` (default `false`) lets the LLM name another user
 * (who must read the namespace and have the agent deployed).
 */
@Component
class CaseLauncherToolPlugin(
    private val caseLaunchService: CaseLaunchService,
) : ToolPlugin {
    override val integrationType: String = INTEGRATION_TYPE

    override val configSchema: JsonNode = CONFIG_SCHEMA

    override fun provideTools(
        config: JsonNode?,
        configName: String?,
        context: ToolContext?,
    ): List<StandardTool<*>> =
        listOf(
            CreateCaseTool(
                configName = configName,
                caseLaunchService = caseLaunchService,
                allowedAgents = config.stringList("agents").ifEmpty { listOf("*") },
                deniedAgents = config.stringList("deniedAgents").toSet(),
                allowOtherUsers = config?.get("allowOtherUsers")?.asBoolean(false) ?: false,
            ),
        )

    companion object {
        const val INTEGRATION_TYPE = "CASE_LAUNCHER"

        val CONFIG_SCHEMA: JsonNode =
            jacksonObjectMapper().readTree(
                """
                {
                  "type": "object",
                  "title": "Case Launcher Configuration",
                  "description": "Lets an agent start a new case with another agent. The new case lives on its own: stopping the agent's case does not stop it. It runs in the background, in the same namespace, and its result is not returned to the caller.",
                  "properties": {
                    "agents": {
                      "type": "array",
                      "title": "Allowed Agents",
                      "description": "Glob patterns matching the agent names this integration may start. Use \"*\" for all agents. Examples: [\"*\"], [\"Profile*\", \"Report*\"].",
                      "items": { "type": "string" },
                      "default": ["*"]
                    },
                    "deniedAgents": {
                      "type": "array",
                      "title": "Denied Agents",
                      "description": "Exact agent names (case-insensitive) that are never started, even if they match an allowed pattern.",
                      "items": { "type": "string" },
                      "uniqueItems": true
                    },
                    "allowOtherUsers": {
                      "type": "boolean",
                      "title": "Allow other users",
                      "description": "Lets the agent start the case for a user other than the one it is running for (input field userId). The target user must have access to the namespace and the agent deployed.",
                      "default": false
                    }
                  },
                  "additionalProperties": false
                }
                """.trimIndent(),
            )
    }
}
