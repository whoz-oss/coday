package io.whozoss.agentos.casePlugin

import com.fasterxml.jackson.annotation.JsonIgnoreProperties
import com.fasterxml.jackson.databind.JsonNode
import io.whozoss.agentos.sdk.tool.StandardTool
import io.whozoss.agentos.sdk.tool.ToolContext
import io.whozoss.agentos.sdk.tool.ToolExecutionResult
import java.util.UUID

/**
 * Tool that starts a new case **for a given user** and returns its id: the twin of [FreeCaseLauncherTool], with a
 * required `userId` in its input. It is a tool of its own so that each schema stays fixed: an agent that must only
 * start cases for the current user is granted [FreeCaseLauncherTool] alone, and the grant of this one is a
 * separate decision (`CASE: [ReadCase, FreeCaseLauncher]`).
 *
 * Everything but the user is as for [FreeCaseLauncherTool]: the agent is designated by name, the case is a
 * top-level case that lives on its own, the calling case is only its origin, the calling case's session context is
 * not inherited, and the output is a [CaseLauncherOutput]. The agent allowlist and the hand-off to
 * [CaseLaunchService] are done by [CaseLauncher].
 *
 * Naming the caller itself is accepted for anyone. Any other user requires the caller to be an ADMIN of the
 * namespace, and the target to exist and read the namespace: those rules are owned by [CaseLaunchService], which
 * refuses with `FORBIDDEN`, `TARGET_USER_NOT_FOUND` or `TARGET_NO_ACCESS`. The tool only checks that `userId` is
 * given and is a UUID.
 *
 * @param configName The integration config name, used as tool-name prefix.
 * @param allowedAgents Glob patterns (`*` wildcard, case-insensitive) of the agents this instance
 *   may start. An agent must match one of them and none of [deniedAgents].
 * @param deniedAgents Exact agent names (case-insensitive) that are never started.
 */
class FreeCaseLauncherForUserTool(
    configName: String?,
    caseLaunchService: CaseLaunchService,
    private val allowedAgents: List<String> = listOf("*"),
    deniedAgents: Set<String> = emptySet(),
) : StandardTool<FreeCaseLauncherForUserTool.Input> {
    /**
     * @param agentName Name of the agent that runs the new case.
     * @param prompt The first message the agent receives, sent on behalf of the user. Must be
     *   self-contained: the new case does not see the conversation it was started from.
     * @param userId Internal id of the user the case runs for. Required, and a UUID. Like `agentName` and
     *   `prompt`, a missing one fails the deserialization; a blank one is `MISSING_INPUT`. Only an ADMIN of the
     *   namespace may name a user other than the current one.
     * @param title Name of the new conversation. Omit to derive it from the prompt.
     * @param sessionContext Free key/value data the new case starts with, passed on as given to its first
     *   message. The calling case's own session context is never passed on; the context of the target user is
     *   added by the system and wins on a conflicting key.
     */
    @JsonIgnoreProperties(ignoreUnknown = true)
    data class Input(
        val agentName: String,
        val prompt: String,
        val userId: String,
        val title: String? = null,
        val sessionContext: Map<String, Any?>? = null,
    )

    private val launcher = CaseLauncher(caseLaunchService, allowedAgents, deniedAgents)

    override val name: String = buildToolName(configName)
    override val version: String = "1.0.0"
    override val paramType: Class<Input> = Input::class.java

    override val description: String =
        buildString {
            append(
                "Start a new case for a given user: the given agent works on the prompt in the background, in its " +
                    "own conversation, and this tool returns immediately with the new case id without waiting " +
                    "for any result. Use it to hand off work that does not belong in the current conversation. " +
                    "The new case lives on its own: stopping the current case does not stop it. Unlike " +
                    "delegation, nothing waits for it and its result is never returned here. The prompt must be " +
                    "self-contained, the new case cannot see this conversation, and it does not inherit its " +
                    "context: pass in `sessionContext` only the part the agent needs. " +
                    "The case runs in the current namespace, for the user given by userId, who must have access " +
                    "to the namespace and to the agent. Only an administrator of the namespace can start a case " +
                    "for a user other than the current one.",
            )
            if (allowedAgents != listOf("*")) {
                append(" Agents that can be started: ${allowedAgents.joinToString(", ")}.")
            }
        }

    override val inputSchema: String = INPUT_SCHEMA

    override fun outputSchema(): JsonNode = CaseLauncherOutput.SCHEMA

    override suspend fun execute(
        input: Input?,
        context: ToolContext,
    ): ToolExecutionResult {
        if (input == null || input.agentName.isBlank() || input.prompt.isBlank() || input.userId.isBlank()) {
            return ToolExecutionResult.error("agentName, prompt and userId are required.", errorType = "MISSING_INPUT")
        }
        val targetUserId =
            runCatching { UUID.fromString(input.userId) }.getOrNull()
                ?: return ToolExecutionResult.error("userId must be a valid UUID.", errorType = "INVALID_INPUT")
        return launcher.launch(
            context = context,
            agentName = input.agentName,
            prompt = input.prompt,
            title = input.title,
            sessionContext = input.sessionContext,
            targetUserId = targetUserId,
        )
    }

    companion object {
        /** Name of the tool, bare or after the `__` that follows its integration config name. */
        const val TOOL_NAME = "FreeCaseLauncherForUser"

        fun buildToolName(configName: String?): String = configName?.let { "${it}__$TOOL_NAME" } ?: TOOL_NAME

        /** Whether [toolName] is a `FreeCaseLauncherForUser` tool, whatever its integration config. */
        fun isFreeCaseLauncherForUserTool(toolName: String): Boolean = toolName == TOOL_NAME || toolName.endsWith("__$TOOL_NAME")

        private val INPUT_SCHEMA =
            """
            {
              "type": "object",
              "properties": {
                "agentName": {
                  "type": "string",
                  "description": "Name of the agent that will work on the prompt."
                },
                "prompt": {
                  "type": "string",
                  "description": "Full, self-contained first message for the agent, sent on behalf of the user."
                },
                "userId": {
                  "type": "string",
                  "format": "uuid",
                  "description": "Id of the user the case runs for, as returned by another tool. Only an administrator of the namespace can name a user other than the current one."
                },
                "title": {
                  "type": "string",
                  "description": "Name of the new conversation. Omit to derive it from the prompt."
                },
                "sessionContext": {
                  "type": "object",
                  "description": "Optional key/value data the new case starts with, passed on as given, for example what the prompt is about. Pass only what the agent needs: nothing of the current session context is carried over, and the user's own context (identity, language) is added by the system.",
                  "additionalProperties": true
                }
              },
              "required": ["agentName", "prompt", "userId"],
              "additionalProperties": false
            }
            """.trimIndent()
    }
}
