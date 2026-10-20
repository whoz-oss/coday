package io.whozoss.agentos.casePlugin

import com.fasterxml.jackson.annotation.JsonIgnoreProperties
import com.fasterxml.jackson.databind.JsonNode
import io.whozoss.agentos.sdk.tool.StandardTool
import io.whozoss.agentos.sdk.tool.ToolContext
import io.whozoss.agentos.sdk.tool.ToolExecutionResult

/**
 * Tool that starts a new case **for the current user** and returns its id. To start one for someone else, an
 * agent is granted [FreeCaseLauncherForUserTool] instead (or as well): the two have a fixed schema each, and can be
 * granted separately through the agent's `integrations`.
 *
 * Input: the agent (by name), the prompt (first message, sent on behalf of the user), an optional
 * title (name of the conversation) and an optional session context (free key/value data the new case is shown
 * with its first message). Output: the case id ([CaseLauncherOutput]).
 *
 * The target agent runs in the background with its own conversation and the tool returns at once.
 * It does not wait for a result: that is what separates it from `DELEGATE__delegate`, which awaits
 * its sub-cases. The new case is a top-level case, not a sub-case: stopping the calling case does not
 * stop it. The calling case is only recorded as its origin
 * ([io.whozoss.agentos.caseFlow.Case.originCaseId]), and a case started this way cannot start another one
 * (`LAUNCH_DEPTH_EXCEEDED`).
 *
 * The agent is designated by **name**, not by id: names are unique per scope and are what an
 * `@mention` resolves, whereas an id is an internal value an LLM could only guess.
 *
 * The case runs in the **same namespace** and for the **same user** as the calling run. Who is calling, and from
 * which case, comes from [ToolContext], never from the input: the calling case is [ToolContext.caseId], and a call
 * without one, such as a direct invocation through the API, is refused with `MISSING_CASE_CONTEXT`.
 * The calling case's session context is **not** inherited: [Input.sessionContext] is the only thing of the
 * caller that reaches the new case, passed on as given, so the caller hands over just the relevant part. The
 * tool knows nothing of its format. [CaseLaunchService] adds the context of the user the case runs for (what
 * the `UserContextProvider` returns and the user's preferred language), which wins over the caller's on a
 * conflicting key.
 *
 * The tool validates its input, then [CaseLauncher] applies the agent allowlist of its integration config and hands
 * the rest to [CaseLaunchService]. Another caller can look the tool up by name and call [executeWithJson], reading
 * the case id from its structured output, so that the integration's config applies to it too.
 *
 * There is no `userId` in the schema, and a field of that name that an LLM sends anyway is ignored: the case runs
 * for the caller.
 *
 * @param configName The integration config name, used as tool-name prefix.
 * @param allowedAgents Glob patterns (`*` wildcard, case-insensitive) of the agents this instance
 *   may start. An agent must match one of them and none of [deniedAgents].
 * @param deniedAgents Exact agent names (case-insensitive) that are never started.
 */
class FreeCaseLauncherTool(
    configName: String?,
    caseLaunchService: CaseLaunchService,
    private val allowedAgents: List<String> = listOf("*"),
    private val deniedAgents: Set<String> = emptySet(),
) : StandardTool<FreeCaseLauncherTool.Input> {
    /**
     * @param agentName Name of the agent that runs the new case.
     * @param prompt The first message the agent receives, sent on behalf of the user. Must be
     *   self-contained: the new case does not see the conversation it was started from.
     * @param title Name of the new conversation. Omit to derive it from the prompt.
     * @param sessionContext Free key/value data the new case starts with, passed on as given to its first
     *   message, for example what the prompt is about. The calling case's own session context is never passed
     *   on; the context of the user is added by the system and wins on a conflicting key.
     */
    @JsonIgnoreProperties(ignoreUnknown = true)
    data class Input(
        val agentName: String,
        val prompt: String,
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
                "Start a new case: the given agent works on the prompt in the background, in its own " +
                    "conversation, and this tool returns immediately with the new case id without waiting " +
                    "for any result. Use it to hand off work that does not belong in the current conversation. " +
                    "The new case lives on its own: stopping the current case does not stop it. Unlike " +
                    "delegation, nothing waits for it and its result is never returned here. The prompt must be " +
                    "self-contained, the new case cannot see this conversation, and it does not inherit its " +
                    "context: pass in `sessionContext` only the part the agent needs. " +
                    "The case runs for the current user, in the current namespace.",
            )
            if (allowedAgents != listOf("*")) {
                append(" Agents that can be started: ${allowedAgents.joinToString(", ")}.")
            }
            if (deniedAgents.isNotEmpty()) {
                append(" Agents that can never be started: ${deniedAgents.joinToString(", ")}.")
            }
        }

    override val inputSchema: String = INPUT_SCHEMA

    override fun outputSchema(): JsonNode = CaseLauncherOutput.SCHEMA

    override suspend fun execute(
        input: Input?,
        context: ToolContext,
    ): ToolExecutionResult =
        when {
            input == null || input.agentName.isBlank() || input.prompt.isBlank() ->
                ToolExecutionResult.error("Both agentName and prompt are required.", errorType = "MISSING_INPUT")

            else ->
                launcher.start(
                    context = context,
                    agentName = input.agentName,
                    prompt = input.prompt,
                    title = input.title,
                    sessionContext = input.sessionContext,
                    targetUserId = null,
                )
        }

    companion object {
        /** Name of the tool, bare or after the `__` that follows its integration config name. */
        const val TOOL_NAME = "FreeCaseLauncher"

        fun buildToolName(configName: String?): String = configName?.let { "${it}__$TOOL_NAME" } ?: TOOL_NAME

        /** Whether [toolName] is a `FreeCaseLauncher` tool, whatever its integration config. */
        fun isFreeCaseLauncherTool(toolName: String): Boolean = toolName == TOOL_NAME || toolName.endsWith("__$TOOL_NAME")

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
              "required": ["agentName", "prompt"],
              "additionalProperties": false
            }
            """.trimIndent()
    }
}
