package io.whozoss.agentos.agentosPlugin

import com.fasterxml.jackson.module.kotlin.jacksonObjectMapper
import io.whozoss.agentos.agentConfig.AgentConfig
import io.whozoss.agentos.sdk.tool.StandardTool
import io.whozoss.agentos.sdk.tool.ToolContext
import io.whozoss.agentos.sdk.tool.ToolExecutionResult
import java.util.UUID

/**
 * Lists all [AgentConfig] entries visible in the current namespace.
 *
 * Requires Namespace READ permission. Returns a JSON array of agent summaries.
 */
class ListAgentsTool(
    private val configName: String?,
    private val listAgents: (namespaceId: UUID, userId: UUID?, withDisabled: Boolean) -> List<AgentConfig>?,
) : StandardTool<ListAgentsTool.Input> {

    data class Input(val withDisabled: Boolean = true)

    override val name: String = if (configName != null) "${configName}__ListAgents" else "ListAgents"
    override val description: String =
        "List all agent configurations in the current namespace. " +
            "Returns id, name, description, enabled status and model name for each agent."
    override val version: String = "1.0.0"
    override val paramType: Class<Input> = Input::class.java
    override val inputSchema: String = INPUT_SCHEMA

    override suspend fun execute(
        input: Input?,
        context: ToolContext,
    ): ToolExecutionResult {
        val withDisabled = input?.withDisabled ?: true
        val agents = listAgents(context.namespaceId, context.userId, withDisabled)
            ?: return ToolExecutionResult.error(
                output = "Permission denied: you do not have READ access to this namespace.",
                errorType = "PERMISSION_DENIED",
            )
        if (agents.isEmpty()) return ToolExecutionResult.success("No agents found in this namespace.")
        val summary = agents.map { agent ->
            mapOf(
                "id" to agent.id.toString(),
                "name" to agent.name,
                "description" to agent.description,
                "enabled" to agent.enabled,
                "modelName" to agent.modelName,
            )
        }
        return ToolExecutionResult.success(mapper.writeValueAsString(summary))
    }

    companion object {
        private val mapper = jacksonObjectMapper()

        val INPUT_SCHEMA: String =
            """
            {
                "type": "object",
                "properties": {
                    "withDisabled": {
                        "type": "boolean",
                        "description": "When true (default), returns all agents including disabled ones. When false, only returns enabled agents.",
                        "default": true
                    }
                },
                "additionalProperties": false
            }
            """.trimIndent()
    }
}
