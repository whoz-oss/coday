package io.whozoss.agentos.queryUser

import io.whozoss.agentos.agent.AgentInterrupt
import io.whozoss.agentos.sdk.caseEvent.QuestionType
import io.whozoss.agentos.sdk.tool.StandardTool
import io.whozoss.agentos.sdk.tool.ToolContext
import io.whozoss.agentos.sdk.tool.ToolExecutionResult

/**
 * Internal tool that asks the user a question.
 *
 * The agent calls this tool when it genuinely cannot proceed without information that
 * only the user can provide — for example a preference, a clarification, or a decision
 * that cannot be inferred from the conversation history or available data sources.
 *
 * **Use sparingly.** Do NOT use this tool:
 * - to confirm destructive actions (use the confirmation gate instead, configured via
 *   [io.whozoss.agentos.sdk.tool.StandardTool.getConfirmationMode]);
 * - to be polite or to summarise what you are about to do;
 * - when a reasonable default exists and the user can always correct it afterward.
 *
 * ## Execution contract
 *
 * - When [input] is null or [Input.question] is blank, [execute] returns a
 *   human-readable [ToolExecutionResult.error] so the LLM can surface the problem
 *   gracefully without crashing the run.
 * - On the happy path, [execute] throws [AgentInterrupt.AwaitAnswer] — a control-flow
 *   signal, not an error. The caller
 *   ([io.whozoss.agentos.agent.AgentSimple]'s tool-callback wrapper or
 *   [io.whozoss.agentos.agent.AgentAdvanced.handleToolExecution]) emits
 *   [io.whozoss.agentos.sdk.caseEvent.ToolRequestEvent] and
 *   [io.whozoss.agentos.sdk.caseEvent.ToolResponseEvent] before the exception propagates,
 *   so the event history is always well-formed.
 * - [AgentInterruptHandler.emitInterruptAndFinishEvents] then emits
 *   [io.whozoss.agentos.sdk.caseEvent.AgentFinishedEvent] (closing the turn) followed by
 *   a [io.whozoss.agentos.sdk.caseEvent.QuestionEvent] addressed to any user of the case.
 * - The run resumes automatically via the pre-flight check in
 *   [io.whozoss.agentos.caseFlow.CaseRuntime.run] once the user's answer has been stored.
 *
 * @param configName The [io.whozoss.agentos.integrationConfig.IntegrationConfig] name
 *   used as tool-name prefix (e.g. `"QUERY_USER__queryUser"`). Null for the bare name.
 */
class QueryUserTool(
    private val configName: String? = null,
) : StandardTool<QueryUserTool.Input> {
    /**
     * @param question The question text to display to the user.
     * @param options Optional list of answer choices. Null or empty → free-text input.
     *   Non-empty → the UI renders buttons. Combine with [allowCustomAnswer] to decide
     *   whether the user may also type a custom answer.
     * @param allowCustomAnswer When true and [options] is non-empty, the UI adds a free-text
     *   field alongside the option buttons ([QuestionType.OPEN_CHOICE]).
     *   When false (default), only the listed options are accepted ([QuestionType.SINGLE_CHOICE]).
     *   Ignored when [options] is null or empty.
     */
    data class Input(
        val question: String,
        val options: List<String>? = null,
        val allowCustomAnswer: Boolean = false,
    )

    override val name: String = configName?.let { "${it}__queryUser" } ?: "queryUser"

    override val description: String =
        """
        Ask the user a question. The run resumes automatically with the user's answer
        in the conversation history.

        IMPORTANT — only use this tool when:
        - The information is genuinely required to proceed and cannot be inferred, guessed, or
          found through any available tool or data source.
        - There is no reasonable default that the user could correct afterward.

        Do NOT use this tool:
        - to confirm destructive actions (use the built-in confirmation gate instead);
        - to be polite, to summarise what you are about to do, or to check in;
        - when you already have enough information to act.

        For multiple-choice questions, provide the options list. Set allowCustomAnswer=true
        if the user should also be able to type a custom answer beyond the listed options.
        """.trimIndent()

    override val inputSchema: String =
        """
        {
          "type": "object",
          "properties": {
            "question": {
              "type": "string",
              "description": "The question to display to the user."
            },
            "options": {
              "type": "array",
              "items": { "type": "string" },
              "description": "Optional list of answer choices rendered as buttons. Omit for free-text input."
            },
            "allowCustomAnswer": {
              "type": "boolean",
              "description": "When true and options are provided, the user may also type a custom answer. Default: false.",
              "default": false
            }
          },
          "required": ["question"]
        }
        """.trimIndent()

    override val version: String = "1.0.0"
    override val paramType: Class<Input> = Input::class.java

    /**
     * Validates input, derives the [QuestionType], and throws [AgentInterrupt.AwaitAnswer]
     * to hand control back to the orchestrator.
     *
     * Returns a human-readable [ToolExecutionResult.error] when [input] is null or
     * [Input.question] is blank — the LLM receives this string and can surface it as a
     * plain-language message to the user without crashing the run.
     *
     * On the happy path, throws [AgentInterrupt.AwaitAnswer] — this is a control-flow
     * signal, not an error (see class KDoc).
     */
    override suspend fun execute(
        input: Input?,
        context: ToolContext,
    ): ToolExecutionResult {
        if (input == null || input.question.isBlank()) {
            return ToolExecutionResult.error("A question is required.", errorType = "MISSING_INPUT")
        }

        val hasOptions = !input.options.isNullOrEmpty()
        val questionType =
            when {
                !hasOptions -> QuestionType.FREE_TEXT
                input.allowCustomAnswer -> QuestionType.OPEN_CHOICE
                else -> QuestionType.SINGLE_CHOICE
            }
        // Normalise: pass null when there are no options so the QuestionEvent is clean.
        val options = input.options?.takeIf { it.isNotEmpty() }

        throw AgentInterrupt.AwaitAnswer(
            question = input.question,
            options = options,
            questionType = questionType,
            // userId identifies the user for whom the agent is running — the one whose
            // answer is awaited. Null when the execution context has no resolved user
            // (webhook, system call, etc.). No fallback is applied: if it is null here,
            // the QuestionEvent remains addressed to any user of the case.
            userId = context.userId,
        )
    }
}
