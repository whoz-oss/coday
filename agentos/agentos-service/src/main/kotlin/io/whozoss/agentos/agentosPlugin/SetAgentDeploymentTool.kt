package io.whozoss.agentos.agentosPlugin

import com.fasterxml.jackson.module.kotlin.jacksonObjectMapper
import io.whozoss.agentos.sdk.tool.StandardTool
import io.whozoss.agentos.sdk.tool.ToolContext
import io.whozoss.agentos.sdk.tool.ToolExecutionResult

/**
 * Deploys or undeploys an [io.whozoss.agentos.agentConfig.AgentConfig] on the current namespace.
 *
 * Requires Namespace WRITE permission.
 *
 * A DEPLOYED_TO relationship from the agent to the namespace is what makes the agent
 * accessible to namespace members at runtime. Without it, even an enabled agent is
 * invisible to members (only super-admins bypass this requirement).
 */
class SetAgentDeploymentTool(
    private val configName: String?,
    private val operations: AgentAdminOperations,
) : StandardTool<SetAgentDeploymentTool.Input> {

    data class Input(val name: String, val deployed: Boolean?)

    override val name: String = if (configName != null) "${configName}__SetAgentDeployment" else "SetAgentDeployment"
    override val description: String =
        "Deploy or undeploy an agent on the current namespace. " +
            "Set deployed=true to create a DEPLOYED_TO relationship between the agent and the namespace, " +
            "making the agent accessible to namespace members at runtime. " +
            "Set deployed=false to remove that relationship. " +
            "IMPORTANT: both enabled (via SetAgentEnabled) AND deployed (this tool) are required " +
            "for namespace members to use an agent — these are two independent conditions. " +
            "A newly created agent is neither enabled nor deployed until both are set explicitly. " +
            "Requires namespace admin rights."
    override val version: String = "1.0.0"
    override val paramType: Class<Input> = Input::class.java
    override val inputSchema: String = INPUT_SCHEMA

    override suspend fun execute(
        input: Input?,
        context: ToolContext,
    ): ToolExecutionResult {
        if (input == null) return ToolExecutionResult.error(
            output = "Missing required parameters: name and deployed.",
            errorType = "INVALID_INPUT",
        )
        if (input.deployed == null) return ToolExecutionResult.error(
            output = "Missing required parameter: deployed (must be true or false).",
            errorType = "INVALID_INPUT",
        )
        val agent = when (input.deployed) {
            true -> operations.deployAgentOnNamespace(context.namespaceId, context.userId, input.name)
            false -> operations.undeployAgentFromNamespace(context.namespaceId, context.userId, input.name)
        } ?: return ToolExecutionResult.error(
            output = "Agent '${input.name}' not found, permission denied, or deployment operation failed.",
            errorType = "NOT_FOUND",
        )
        val result = mapOf(
            "id" to agent.id.toString(),
            "name" to agent.name,
            "deployed" to input.deployed,
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
                        "description": "Name of the agent to deploy or undeploy (case-insensitive)."
                    },
                    "deployed": {
                        "type": "boolean",
                        "description": "true to deploy the agent on the namespace (making it accessible to members), false to undeploy it."
                    }
                },
                "required": ["name", "deployed"],
                "additionalProperties": false
            }
            """.trimIndent()
    }
}
