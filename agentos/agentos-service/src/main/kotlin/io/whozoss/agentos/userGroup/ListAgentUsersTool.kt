package io.whozoss.agentos.userGroup

import com.fasterxml.jackson.databind.JsonNode
import com.fasterxml.jackson.module.kotlin.jacksonObjectMapper
import io.whozoss.agentos.sdk.tool.StandardTool
import io.whozoss.agentos.sdk.tool.ToolContext
import io.whozoss.agentos.sdk.tool.ToolExecutionResult
import java.util.UUID

/**
 * Tool that lists all users who have access to a given agent via UserGroup deployment.
 *
 * A user has access to an agent when they are a member (MEMBER or ADMIN) of at least one
 * UserGroup that has the agent deployed to it via a [:DEPLOYED_TO] edge. Users belonging
 * to multiple qualifying groups are deduplicated.
 *
 * Input:  [agentConfigId] — the UUID of the AgentConfig.
 * Output: textual summary + [ToolExecutionResult.structuredOutput] JSON array of members.
 *
 * @param configName The [io.whozoss.agentos.integrationConfig.IntegrationConfig] name used
 *   as tool-name prefix. Null for the bare name.
 * @param usersByAgentLoader Lambda that resolves members for a given agent UUID.
 */
class ListAgentUsersTool(
    private val configName: String? = null,
    private val usersByAgentLoader: (UUID) -> List<UserGroupMember>,
) : StandardTool<ListAgentUsersTool.Input> {

    data class Input(val agentConfigId: UUID)

    override val name: String = configName?.let { "${it}__listAgentUsers" } ?: "listAgentUsers"

    override val description: String =
        """
        List all users who have access to a given agent through UserGroup deployment.

        A user is included when they are a member (MEMBER or ADMIN) of at least one UserGroup
        that has the agent deployed to it. Users belonging to multiple qualifying groups are
        returned only once.

        Use this tool when you need to know the full audience of an agent — for example to
        fan out a task, send a notification, or audit who can interact with it.
        """.trimIndent()

    override val inputSchema: String =
        """
        {
          "type": "object",
          "properties": {
            "agentConfigId": {
              "type": "string",
              "format": "uuid",
              "description": "The UUID of the AgentConfig whose audience you want to list."
            }
          },
          "required": ["agentConfigId"]
        }
        """.trimIndent()

    override val version: String = "1.0.0"
    override val paramType: Class<Input> = Input::class.java

    override fun outputSchema(): JsonNode = MEMBER_ARRAY_SCHEMA

    override suspend fun execute(input: Input?, context: ToolContext): ToolExecutionResult {
        if (input == null) {
            return ToolExecutionResult.error("agentConfigId is required.", errorType = "MISSING_INPUT")
        }
        val members = try {
            usersByAgentLoader(input.agentConfigId)
        } catch (e: Exception) {
            return ToolExecutionResult.error(
                "Failed to retrieve users for agent ${input.agentConfigId}: ${e.message}",
                errorType = "RETRIEVAL_ERROR",
            )
        }
        val structuredOutput = mapper.valueToTree<JsonNode>(members.toStructuredList())
        val summary = if (members.isEmpty()) {
            "Agent ${input.agentConfigId} has no users deployed through any UserGroup."
        } else {
            "Agent ${input.agentConfigId} is accessible to ${members.size} user(s): " +
                members.joinToString(", ") { "${it.externalId} (${it.role})" }
        }
        return ToolExecutionResult.success(output = summary, structuredOutput = structuredOutput)
    }

    companion object {
        private val mapper = jacksonObjectMapper()
        internal val MEMBER_ARRAY_SCHEMA: JsonNode = mapper.readTree(
            """
            {
              "type": "array",
              "items": {
                "type": "object",
                "properties": {
                  "userId":     { "type": "string", "format": "uuid" },
                  "externalId": { "type": "string" },
                  "role":       { "type": "string", "enum": ["ADMIN", "MEMBER"] },
                  "email":      { "type": ["string", "null"] },
                  "firstname":  { "type": ["string", "null"] },
                  "lastname":   { "type": ["string", "null"] }
                },
                "required": ["userId", "externalId", "role"]
              }
            }
            """.trimIndent(),
        )
    }
}

internal fun List<UserGroupMember>.toStructuredList(): List<Map<String, Any?>> =
    map { m ->
        mapOf(
            "userId" to m.userId.toString(),
            "externalId" to m.externalId,
            "role" to m.role,
            "email" to m.email,
            "firstname" to m.firstname,
            "lastname" to m.lastname,
        )
    }
