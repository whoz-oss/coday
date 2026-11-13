package io.whozoss.agentos.userGroup

import io.whozoss.agentos.sdk.tool.ToolContext
import io.whozoss.agentos.sdk.tool.ToolExecutionResult
import io.whozoss.agentos.util.coRunCatching
import mu.KLogging
import java.util.UUID

/**
 * Retrieval shared by the tools exposing "the users of an agent" ([ListAgentUsersTool], [SearchAgentUsersTool]):
 * input check, loader call (which enforces the permissions), error mapping. Only the shape of the successful
 * output differs between the tools, and is built by [onSuccess].
 *
 * Errors (identical for all callers):
 * - null [input] → `MISSING_INPUT`
 * - the loader throws → `RETRIEVAL_ERROR` (the exception message is logged, never returned)
 * - the loader returns null (no access) → `NOT_FOUND`
 *
 * @param toolName name of the calling tool, used as log prefix.
 * @param usersByAgentLoader Lambda: (agentConfigId, namespaceId, userId) → members, or null.
 * @param onSuccess builds the result from the agent id and its members.
 */
internal suspend fun retrieveAgentUsers(
    toolName: String,
    input: ListAgentUsersTool.Input?,
    context: ToolContext,
    usersByAgentLoader: suspend (agentConfigId: UUID, namespaceId: UUID, userId: UUID?) -> List<UserGroupMember>?,
    onSuccess: (agentConfigId: UUID, members: List<UserGroupMember>) -> ToolExecutionResult,
): ToolExecutionResult {
    if (input == null) {
        return ToolExecutionResult.error("agentConfigId is required.", errorType = "MISSING_INPUT")
    }
    val members =
        coRunCatching { usersByAgentLoader(input.agentConfigId, context.namespaceId, context.userId) }
            .onFailure { e ->
                AgentUsersRetrieval.logger.warn(e) { "[$toolName] Failed to retrieve users for agent ${input.agentConfigId}" }
            }.getOrElse {
                return ToolExecutionResult.error(
                    "Failed to retrieve users for agent ${input.agentConfigId}.",
                    errorType = "RETRIEVAL_ERROR",
                )
            }
            ?: return ToolExecutionResult.error(
                "You do not have access to list users for agent ${input.agentConfigId}.",
                errorType = "NOT_FOUND",
            )
    return onSuccess(input.agentConfigId, members)
}

/** Textual summary of the audience of an agent, shared by the tools of [retrieveAgentUsers]. */
internal fun agentUsersSummary(
    agentConfigId: UUID,
    members: List<UserGroupMember>,
): String =
    if (members.isEmpty()) {
        "Agent $agentConfigId has no users deployed through any UserGroup in this namespace."
    } else {
        "Agent $agentConfigId is accessible to ${members.size} user(s): " +
            members.joinToString(", ") { "${it.externalId} (${it.role})" }
    }

/** Input schema shared by the tools of [retrieveAgentUsers]. */
internal val AGENT_USERS_INPUT_SCHEMA: String =
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

private object AgentUsersRetrieval : KLogging()
