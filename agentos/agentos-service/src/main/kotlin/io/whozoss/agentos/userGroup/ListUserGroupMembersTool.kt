package io.whozoss.agentos.userGroup

import com.fasterxml.jackson.databind.JsonNode
import com.fasterxml.jackson.module.kotlin.jacksonObjectMapper
import io.whozoss.agentos.sdk.tool.StandardTool
import io.whozoss.agentos.sdk.tool.ToolContext
import io.whozoss.agentos.sdk.tool.ToolExecutionResult
import java.util.UUID

/**
 * Tool that lists all users belonging to a user group.
 *
 * Input: [userGroupId] — the UUID of the user group to inspect.
 * Output: textual summary + [ToolExecutionResult.structuredOutput] containing the array of
 * user UUIDs so callers can process the result without parsing free-form text.
 *
 * Authorization note: the tool does not enforce UserGroup READ permission itself — the
 * caller (agent + user) is expected to only be granted this integration for groups they
 * are allowed to inspect. The REST equivalent (`GET /api/user-groups/{userGroupId}/members`)
 * enforces READ via `@PreAuthorize`; an agent tool call does not go through that path.
 *
 * @param configName The [io.whozoss.agentos.integrationConfig.IntegrationConfig] name used
 *   as tool-name prefix (e.g. `"USER_GROUP__listUserGroupMembers"`). Null for the bare name.
 * @param membersLoader Lambda that resolves members for a given group UUID. Injected at
 *   construction time by [ListUserGroupMembersConfiguration] (via [UserGroupRepository])
 *   so the tool itself is stateless and free of Spring dependencies.
 */
class ListUserGroupMembersTool(
    private val configName: String? = null,
    private val membersLoader: (UUID) -> List<UserGroupMember>,
) : StandardTool<ListUserGroupMembersTool.Input> {

    data class Input(
        val userGroupId: UUID,
    )

    override val name: String = configName?.let { "${it}__listMembers" } ?: "listMembers"

    override val description: String =
        """
        List all users belonging to a user group.

        Returns the internal AgentOS UUID for each member of the group, together with their
        external identity-provider key (e.g. email) and their role (ADMIN or MEMBER).

        Use this tool when you need to know which users are part of a specific group —
        for example to fan out a task, audit membership, or check whether a particular
        user is included.
        """.trimIndent()

    override val inputSchema: String =
        """
        {
          "type": "object",
          "properties": {
            "userGroupId": {
              "type": "string",
              "format": "uuid",
              "description": "The UUID of the user group whose members you want to list."
            }
          },
          "required": ["userGroupId"]
        }
        """.trimIndent()

    override val version: String = "1.0.0"

    override val paramType: Class<Input> = Input::class.java

    /**
     * Structured-output schema: an array of objects, each carrying the fields returned by
     * [UserGroupMember] that are most useful for downstream automation (userId, externalId,
     * role). Display fields (email, firstname, lastname) are included so callers can build
     * human-readable output without a second lookup.
     */
    override fun outputSchema(): JsonNode =
        mapper.readTree(
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

    override suspend fun execute(
        input: Input?,
        context: ToolContext,
    ): ToolExecutionResult {
        if (input == null) {
            return ToolExecutionResult.error("userGroupId is required.", errorType = "MISSING_INPUT")
        }

        val members =
            try {
                membersLoader(input.userGroupId)
            } catch (e: Exception) {
                return ToolExecutionResult.error(
                    "Failed to retrieve members for group ${input.userGroupId}: ${e.message}",
                    errorType = "RETRIEVAL_ERROR",
                )
            }

        val structuredOutput = mapper.valueToTree<JsonNode>(members.toStructuredList())

        val summary =
            if (members.isEmpty()) {
                "User group ${input.userGroupId} has no members."
            } else {
                "User group ${input.userGroupId} has ${members.size} member(s): " +
                    members.joinToString(", ") { "${it.externalId} (${it.role})" }
            }

        return ToolExecutionResult.success(
            output = summary,
            structuredOutput = structuredOutput,
        )
    }

    companion object {
        private val mapper = jacksonObjectMapper()
    }
}
