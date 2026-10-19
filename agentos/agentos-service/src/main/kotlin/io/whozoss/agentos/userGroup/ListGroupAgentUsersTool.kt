package io.whozoss.agentos.userGroup

import com.fasterxml.jackson.databind.JsonNode
import com.fasterxml.jackson.module.kotlin.jacksonObjectMapper
import io.whozoss.agentos.sdk.tool.StandardTool
import io.whozoss.agentos.sdk.tool.ToolContext
import io.whozoss.agentos.sdk.tool.ToolExecutionResult
import java.util.UUID

/**
 * Tool that lists all members of a UserGroup — but only when the given agent is deployed
 * to that group. Returns an empty list (not an error) when the agent is not deployed there.
 *
 * This is the intersection check: "which users in this group can reach this agent?". Because
 * deployment is a prerequisite, the answer is always either the full group membership or nothing.
 *
 * Input:  [userGroupId] + [agentConfigId]
 * Output: textual summary + [ToolExecutionResult.structuredOutput] JSON array of members.
 *
 * @param configName The [io.whozoss.agentos.integrationConfig.IntegrationConfig] name used
 *   as tool-name prefix. Null for the bare name.
 * @param membersByGroupAndAgentLoader Lambda that resolves members for a (group, agent) pair.
 */
class ListGroupAgentUsersTool(
    private val configName: String? = null,
    private val membersByGroupAndAgentLoader: (userGroupId: UUID, agentConfigId: UUID) -> List<UserGroupMember>,
) : StandardTool<ListGroupAgentUsersTool.Input> {

    data class Input(val userGroupId: UUID, val agentConfigId: UUID)

    override val name: String = configName?.let { "${it}__listGroupAgentUsers" } ?: "listGroupAgentUsers"

    override val description: String =
        """
        List all members of a UserGroup who have access to a given agent.

        The agent must be deployed to the group (via a DEPLOYED_TO edge). When it is, all
        members of the group (MEMBER and ADMIN) are returned. When it is not, an empty list
        is returned — this is not an error, it simply means the group has no access to that agent.

        Use this tool when you need to know the subset of a group that can interact with a
        specific agent — for example to target a scheduled task at the right audience.
        """.trimIndent()

    override val inputSchema: String =
        """
        {
          "type": "object",
          "properties": {
            "userGroupId": {
              "type": "string",
              "format": "uuid",
              "description": "The UUID of the UserGroup to inspect."
            },
            "agentConfigId": {
              "type": "string",
              "format": "uuid",
              "description": "The UUID of the AgentConfig that must be deployed to the group."
            }
          },
          "required": ["userGroupId", "agentConfigId"]
        }
        """.trimIndent()

    override val version: String = "1.0.0"
    override val paramType: Class<Input> = Input::class.java

    override fun outputSchema(): JsonNode = ListAgentUsersTool.MEMBER_ARRAY_SCHEMA

    override suspend fun execute(input: Input?, context: ToolContext): ToolExecutionResult {
        if (input == null) {
            return ToolExecutionResult.error(
                "userGroupId and agentConfigId are required.",
                errorType = "MISSING_INPUT",
            )
        }
        val members = try {
            membersByGroupAndAgentLoader(input.userGroupId, input.agentConfigId)
        } catch (e: Exception) {
            return ToolExecutionResult.error(
                "Failed to retrieve members for group ${input.userGroupId} " +
                    "and agent ${input.agentConfigId}: ${e.message}",
                errorType = "RETRIEVAL_ERROR",
            )
        }
        val structuredOutput = jacksonObjectMapper().valueToTree<JsonNode>(members.toStructuredList())
        val summary = if (members.isEmpty()) {
            "Agent ${input.agentConfigId} is not deployed to group ${input.userGroupId}, " +
                "or the group has no members."
        } else {
            "Group ${input.userGroupId} has ${members.size} member(s) with access to " +
                "agent ${input.agentConfigId}: " +
                members.joinToString(", ") { "${it.externalId} (${it.role})" }
        }
        return ToolExecutionResult.success(output = summary, structuredOutput = structuredOutput)
    }
}
