package io.whozoss.agentos.agentosPlugin

import com.fasterxml.jackson.module.kotlin.jacksonObjectMapper
import io.whozoss.agentos.sdk.tool.StandardTool
import io.whozoss.agentos.sdk.tool.ToolContext
import io.whozoss.agentos.sdk.tool.ToolExecutionResult

/**
 * Disables (unpublishes) an [io.whozoss.agentos.agentConfig.AgentConfig] in the current namespace.
 *
 * Requires AgentConfig WRITE permission.
 */
class DisableAgentTool(
    private val configName: String?,
    private val operations: AgentAdminOperations,
) : StandardTool<DisableAgentTool.Input> {

    data class Input(val name: String)

    override val name: String = if (configName != null) "${configName}__DisableAgent" else "DisableAgent"
    override val description: String =
        "Disable (unpublish) an agent in the current namespace, hiding it from end-users. " +
            "Requires namespace admin rights."
    override val version: String = "1.0.0"
    override val paramType: Class<Input> = Input::class.java
    override val inputSchema: String = INPUT_SCHEMA

    override suspend fun execute(
        input: Input?,
        context: ToolContext,
    ): ToolExecutionResult {
        if (input == null) return ToolExecutionResult.error(
            output = "Missing required parameter: name",
            errorType = "INVALID_INPUT",
        )
        val agent = operations.disableAgent(context.namespaceId, context.userId, input.name)
            ?: return ToolExecutionResult.error(
                output = "Agent '${input.name}' not found or permission denied.",
                errorType = "NOT_FOUND",
            )
        val result = mapOf(
            "id" to agent.id.toString(),
            "name" to agent.name,
            "enabled" to agent.enabled,
        )
        return ToolExecutionResult.success(mapper.writeValueAsString(result))
    }

    companion object {
        private val mapper = jacksonObjectMapper()

        val INPUT_SCHEMA: String =
            """
            {
                "type": "object",
                "properties": {
                    "name": {
                        "type": "string",
                        "description": "Name of the agent to disable (case-insensitive)."
                    }
                },
                "required": ["name"],
                "additionalProperties": false
            }
            """.trimIndent()
    }
}
