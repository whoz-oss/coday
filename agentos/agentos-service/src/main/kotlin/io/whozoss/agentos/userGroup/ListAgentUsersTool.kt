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
 * UserGroup — within the caller's namespace — that has the agent deployed to it.
 * Users belonging to multiple qualifying groups are deduplicated.
 *
 * ## Authorization
 *
 * The [usersByAgentLoader] lambda (wired in [UserGroupToolPluginConfiguration]) requires
 * the caller to hold Namespace READ, which is the same floor that
 * `GET /api/user-groups?namespaceId=` enforces. Anonymous calls fail-closed (null return).
 *
 * A null return is mapped to NOT_FOUND / access-denied.
 *
 * @param configName IntegrationConfig name used as tool-name prefix. Null for bare name.
 * @param usersByAgentLoader Lambda: (agentConfigId, namespaceId, userId) → members, or null.
 */
class ListAgentUsersTool(
    private val configName: String? = null,
    private val usersByAgentLoader: suspend (agentConfigId: UUID, namespaceId: UUID, userId: UUID?) -> List<UserGroupMember>?,
) : StandardTool<ListAgentUsersTool.Input> {
    data class Input(
        val agentConfigId: UUID,
    )

    override val name: String = configName?.let { "${it}__listAgentUsers" } ?: "listAgentUsers"

    override val description: String =
        """
        List all users who have access to a given agent through UserGroup deployment.

        A user is included when they are a member (MEMBER or ADMIN) of at least one UserGroup
        in the current namespace that has the agent deployed to it. Users belonging to multiple
        qualifying groups are returned only once.

        Use this tool when you need to know the full audience of an agent — for example to
        fan out a task, send a notification, or audit who can interact with it.

        Note: you need the agent UUID. If you don't have it, ask the user or look it up
        through another available tool before calling this one.
        """.trimIndent()

    override val inputSchema: String = AGENT_USERS_INPUT_SCHEMA

    override val version: String = "1.0.0"
    override val paramType: Class<Input> = Input::class.java

    override fun outputSchema(): JsonNode = MEMBER_ARRAY_SCHEMA

    override suspend fun execute(
        input: Input?,
        context: ToolContext,
    ): ToolExecutionResult =
        retrieveAgentUsers(name, input, context, usersByAgentLoader) { agentConfigId, members ->
            ToolExecutionResult.success(
                output = agentUsersSummary(agentConfigId, members),
                structuredOutput = mapper.valueToTree<JsonNode>(members.toStructuredList()),
            )
        }

    companion object {
        private val mapper = jacksonObjectMapper()

        /** Shared output schema for all USER_GROUP member-listing tools. */
        val MEMBER_ARRAY_SCHEMA: JsonNode =
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
    }
}
