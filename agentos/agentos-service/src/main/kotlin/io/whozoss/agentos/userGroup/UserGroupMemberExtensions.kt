package io.whozoss.agentos.userGroup

/**
 * Converts a list of [UserGroupMember]s to the structured map format used by the
 * [ToolExecutionResult.structuredOutput][io.whozoss.agentos.sdk.tool.ToolExecutionResult.structuredOutput]
 * of the USER_GROUP tools.
 *
 * Shared by [ListUserGroupMembersTool], [ListAgentUsersTool], and [ListGroupAgentUsersTool]
 * to avoid repeating the same six-field mapping.
 */
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
