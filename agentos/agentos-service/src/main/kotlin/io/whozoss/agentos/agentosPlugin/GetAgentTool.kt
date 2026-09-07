package io.whozoss.agentos.agentosPlugin

import com.fasterxml.jackson.module.kotlin.jacksonObjectMapper
import io.whozoss.agentos.agentConfig.AgentConfig
import io.whozoss.agentos.sdk.tool.StandardTool
import io.whozoss.agentos.sdk.tool.ToolContext
import io.whozoss.agentos.sdk.tool.ToolExecutionResult
import java.util.UUID

/**
 * Retrieves a single [AgentConfig] by name from the current namespace.
 *
 * Requires AgentConfig READ permission on the found entity. Returns null (NOT_FOUND)
 * when no agent with the given name exists or when the caller lacks READ.
 */
class GetAgentTool(
    private val configName: String?,
    private val getAgent: (namespaceId: UUID, userId: UUID?, name: String) -> AgentConfig?,
) : StandardTool<GetAgentTool.Input> {

    data class Input(val name: String)

    override val name: String = if (configName != null) "${configName}__GetAgent" else "GetAgent"
    override val description: String =
        "Retrieve the full configuration of an agent by name in the current namespace. " +
            "Returns id, name, description, instructions, modelName, integrations, subAgents, enabled status."
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
        val agent = getAgent(context.namespaceId, context.userId, input.name)
            ?: return ToolExecutionResult.error(
                output = "Agent '${input.name}' not found or not accessible in this namespace.",
                errorType = "NOT_FOUND",
            )
        val detail = mapOf(
            "id" to agent.id.toString(),
            "name" to agent.name,
            "description" to agent.description,
            "instructions" to agent.instructions,
            "modelName" to agent.modelName,
            "integrations" to agent.integrations,
            "subAgents" to agent.subAgents,
            "enabled" to agent.enabled,
            "advancedExecution" to agent.advancedExecution,
        )
        return ToolExecutionResult.success(mapper.writeValueAsString(detail))
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
                        "description": "Name of the agent to retrieve (case-insensitive)."
                    }
                },
                "required": ["name"],
                "additionalProperties": false
            }
            """.trimIndent()
    }
}
