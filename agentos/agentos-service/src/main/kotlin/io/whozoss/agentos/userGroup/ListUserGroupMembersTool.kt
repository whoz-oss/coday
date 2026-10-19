package io.whozoss.agentos.userGroup

import com.fasterxml.jackson.databind.JsonNode
import com.fasterxml.jackson.module.kotlin.jacksonObjectMapper
import io.whozoss.agentos.sdk.tool.StandardTool
import io.whozoss.agentos.sdk.tool.ToolContext
import io.whozoss.agentos.sdk.tool.ToolExecutionResult
import mu.KLogging
import java.util.UUID

/**
 * Tool that lists all users belonging to a user group.
 *
 * Input:  [userGroupId] — the UUID of the group to inspect.
 * Output: textual summary + [ToolExecutionResult.structuredOutput] JSON array of members.
 *
 * ## Authorization
 *
 * The [membersLoader] lambda (wired in [UserGroupToolPluginConfiguration]) enforces:
 * - namespace isolation: the group must belong to the caller's namespace
 * - READ permission on the group via [io.whozoss.agentos.permissions.PermissionService]
 * - anonymous calls (userId == null): fail-closed
 *
 * A null return from the loader is mapped to NOT_FOUND, mirroring @HideOnAccessDenied
 * on the REST path (`GET /api/user-groups/{id}/members`).
 *
 * @param configName IntegrationConfig name used as tool-name prefix. Null for bare name.
 * @param membersLoader Lambda: (groupId, namespaceId, userId) → members, or null when the
 *   group does not exist, belongs to a different namespace, or the caller lacks READ.
 */
class ListUserGroupMembersTool(
    private val configName: String? = null,
    private val membersLoader: (groupId: UUID, namespaceId: UUID, userId: UUID?) -> List<UserGroupMember>?,
) : StandardTool<ListUserGroupMembersTool.Input> {

    data class Input(val userGroupId: UUID)

    override val name: String = configName?.let { "${it}__listMembers" } ?: "listMembers"

    override val description: String =
        """
        List all users belonging to a user group.

        Returns the internal AgentOS UUID for each member of the group, together with their
        external identity-provider key (e.g. email) and their role (ADMIN or MEMBER).

        Use this tool when you need to know which users are part of a specific group —
        for example to fan out a task, audit membership, or check whether a particular
        user is included.

        Note: you need the group UUID. If you don't have it, ask the user or look it up
        through another available tool before calling this one.
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

    override fun outputSchema(): JsonNode = MEMBER_ARRAY_SCHEMA

    override suspend fun execute(input: Input?, context: ToolContext): ToolExecutionResult {
        if (input == null) {
            return ToolExecutionResult.error("userGroupId is required.", errorType = "MISSING_INPUT")
        }
        val members = try {
            membersLoader(input.userGroupId, context.namespaceId, context.userId)
        } catch (e: Exception) {
            logger.warn(e) { "[listMembers] Failed to retrieve members for group ${input.userGroupId}" }
            return ToolExecutionResult.error(
                "Failed to retrieve members for group ${input.userGroupId}.",
                errorType = "RETRIEVAL_ERROR",
            )
        } ?: return ToolExecutionResult.error(
            "User group ${input.userGroupId} was not found or you do not have access to it.",
            errorType = "NOT_FOUND",
        )

        val structuredOutput = mapper.valueToTree<JsonNode>(members.toStructuredList())
        val summary = if (members.isEmpty()) {
            "User group ${input.userGroupId} has no members."
        } else {
            "User group ${input.userGroupId} has ${members.size} member(s): " +
                members.joinToString(", ") { "${it.externalId} (${it.role})" }
        }
        return ToolExecutionResult.success(output = summary, structuredOutput = structuredOutput)
    }

    companion object : KLogging() {
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
