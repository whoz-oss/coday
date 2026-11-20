package io.whozoss.agentos.agentosPlugin

import com.fasterxml.jackson.module.kotlin.jacksonObjectMapper
import io.whozoss.agentos.sdk.tool.StandardTool
import io.whozoss.agentos.sdk.tool.ToolContext
import io.whozoss.agentos.sdk.tool.ToolExecutionResult

/**
 * Enables or disables an [io.whozoss.agentos.agentConfig.AgentConfig] in the current namespace.
 *
 * Requires AgentConfig WRITE permission. The [Input.enabled] field is required: the LLM
 * must state the intended state explicitly rather than relying on a default.
 */
class SetAgentEnabledTool(
    private val configName: String?,
    private val operations: AgentAdminOperations,
) : StandardTool<SetAgentEnabledTool.Input> {

    data class Input(val name: String, val enabled: Boolean?)

    override val name: String = if (configName != null) "${configName}__SetAgentEnabled" else "SetAgentEnabled"
    override val description: String =
        "Enable or disable an agent in the current namespace. " +
            "Set enabled=true to publish the agent (making it eligible for deployment), " +
            "or enabled=false to unpublish it. " +
            "Note: enabling an agent is NOT sufficient for namespace members to use it — " +
            "the agent must also be deployed on the namespace via SetAgentDeployment. " +
            "Requires namespace admin rights."
    override val version: String = "1.0.0"
    override val paramType: Class<Input> = Input::class.java
    override val inputSchema: String = INPUT_SCHEMA

    override suspend fun execute(
        input: Input?,
        context: ToolContext,
    ): ToolExecutionResult {
        if (input == null) return ToolExecutionResult.error(
            output = "Missing required parameters: name and enabled.",
            errorType = "INVALID_INPUT",
        )
        if (input.enabled == null) return ToolExecutionResult.error(
            output = "Missing required parameter: enabled (must be true or false).",
            errorType = "INVALID_INPUT",
        )
        val agent = when (input.enabled) {
            true -> operations.enableAgent(context.namespaceId, context.userId, input.name)
            false -> operations.disableAgent(context.namespaceId, context.userId, input.name)
        } ?: return ToolExecutionResult.error(
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
                        "description": "Name of the agent to enable or disable (case-insensitive)."
                    },
                    "enabled": {
                        "type": "boolean",
                        "description": "true to enable (publish) the agent, false to disable (unpublish) it."
                    }
                },
                "required": ["name", "enabled"],
                "additionalProperties": false
            }
            """.trimIndent()
    }
}
