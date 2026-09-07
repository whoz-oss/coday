package io.whozoss.agentos.agentosPlugin

import com.fasterxml.jackson.module.kotlin.jacksonObjectMapper
import io.whozoss.agentos.agentConfig.AgentConfig
import io.whozoss.agentos.sdk.tool.StandardTool
import io.whozoss.agentos.sdk.tool.ToolContext
import io.whozoss.agentos.sdk.tool.ToolExecutionResult
import java.util.UUID

/**
 * Creates a new [AgentConfig] in the current namespace.
 *
 * Requires Namespace WRITE permission. Returns the created agent's id, name and enabled status.
 */
class CreateAgentTool(
    private val configName: String?,
    private val createAgent: (namespaceId: UUID, userId: UUID?, input: Input) -> AgentConfig?,
) : StandardTool<CreateAgentTool.Input> {

    data class Input(
        val name: String,
        val description: String? = null,
        val instructions: String? = null,
        val modelName: String? = null,
        val integrations: Map<String, List<String>?>? = null,
        val subAgents: List<String>? = null,
        val advancedExecution: Boolean = false,
    )

    override val name: String = if (configName != null) "${configName}__CreateAgent" else "CreateAgent"
    override val description: String =
        "Create a new agent configuration in the current namespace. " +
            "Requires namespace admin rights. Returns the created agent id and name."
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
        if (input.name.isBlank()) return ToolExecutionResult.error(
            output = "Agent name must not be blank.",
            errorType = "INVALID_INPUT",
        )
        val created = createAgent(context.namespaceId, context.userId, input)
            ?: return ToolExecutionResult.error(
                output = "Permission denied or agent name '${input.name}' already exists in this namespace.",
                errorType = "PERMISSION_DENIED",
            )
        val result = mapOf(
            "id" to created.id.toString(),
            "name" to created.name,
            "enabled" to created.enabled,
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
                        "description": "Unique name for the new agent within the namespace."
                    },
                    "description": {
                        "type": "string",
                        "description": "Short description of what the agent does."
                    },
                    "instructions": {
                        "type": "string",
                        "description": "System-level instructions for the agent's behaviour."
                    },
                    "modelName": {
                        "type": "string",
                        "description": "AI model name or alias to use. If omitted, the namespace default applies."
                    },
                    "integrations": {
                        "type": "object",
                        "description": "Map of integration name to optional list of allowed tool names (null = all tools).",
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
                        "description": "Glob patterns for agents this agent may delegate to."
                    },
                    "advancedExecution": {
                        "type": "boolean",
                        "description": "When true, uses the multi-step orchestration loop. Defaults to false.",
                        "default": false
                    }
                },
                "required": ["name"],
                "additionalProperties": false
            }
            """.trimIndent()
    }
}
