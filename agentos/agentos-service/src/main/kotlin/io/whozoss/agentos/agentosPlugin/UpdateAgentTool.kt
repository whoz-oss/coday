package io.whozoss.agentos.agentosPlugin

import com.fasterxml.jackson.module.kotlin.jacksonObjectMapper
import io.whozoss.agentos.sdk.tool.StandardTool
import io.whozoss.agentos.sdk.tool.ToolContext
import io.whozoss.agentos.sdk.tool.ToolExecutionResult

/**
 * Updates an existing [io.whozoss.agentos.agentConfig.AgentConfig] in the current namespace.
 *
 * Requires AgentConfig WRITE permission. Only non-null fields in the input are applied;
 * omitted fields preserve their current value. [namespaceId] is never modified (mass-assignment guard).
 */
class UpdateAgentTool(
    private val configName: String?,
    private val operations: AgentAdminOperations,
) : StandardTool<UpdateAgentTool.Input> {

    data class Input(
        val name: String,
        val description: String? = null,
        val instructions: String? = null,
        val modelName: String? = null,
        val integrations: Map<String, List<String>?>? = null,
        val subAgents: List<String>? = null,
        val advancedExecution: Boolean? = null,
    )

    override val name: String = if (configName != null) "${configName}__UpdateAgent" else "UpdateAgent"
    override val description: String =
        "Update an existing agent configuration in the current namespace. " +
            "Identified by name (case-insensitive). Only provided fields are updated; others are preserved. " +
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
        val updated = operations.updateAgent(context.namespaceId, context.userId, input)
            ?: return ToolExecutionResult.error(
                output = "Agent '${input.name}' not found or permission denied.",
                errorType = "NOT_FOUND",
            )
        val result = mapOf(
            "id" to updated.id.toString(),
            "name" to updated.name,
            "enabled" to updated.enabled,
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
                        "description": "Name of the agent to update (case-insensitive)."
                    },
                    "description": {
                        "type": "string",
                        "description": "New description. Omit to keep the current value."
                    },
                    "instructions": {
                        "type": "string",
                        "description": "New system-level instructions. Omit to keep the current value."
                    },
                    "modelName": {
                        "type": "string",
                        "description": "New AI model name or alias. Omit to keep the current value."
                    },
                    "integrations": {
                        "type": "object",
                        "description": "New integrations map (replaces the current one). Omit to keep the current value.",
                        "additionalProperties": {
                            "oneOf": [
                                { "type": "null" },
                                { "type": "array", "items": { "type": "string" } }
                            ]
                        }
                    },
                    "subAgents": {
                        "type": "array",
                        "items": { "type": "string" },
                        "description": "New subAgents glob list. Omit to keep the current value."
                    },
                    "advancedExecution": {
                        "type": "boolean",
                        "description": "Update the advanced execution flag. Omit to keep the current value."
                    }
                },
                "required": ["name"],
                "additionalProperties": false
            }
            """.trimIndent()
    }
}
