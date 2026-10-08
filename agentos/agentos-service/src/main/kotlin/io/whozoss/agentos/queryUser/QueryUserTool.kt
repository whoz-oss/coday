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
 * only the user can provide -- for example a preference, a clarification, or a decision
 * that cannot be inferred from the conversation history or available data sources.
 *
 * **Use sparingly.** Do NOT use this tool:
 * - to confirm destructive actions (use the confirmation gate instead, configured via
 *   [io.whozoss.agentos.sdk.tool.StandardTool.getConfirmationMode]);
 * - to be polite or to summarise what you are about to do;
 * - when a reasonable default exists and the user can always correct it afterward.
 *
 * ## Two layers of constraint on [allowedQuestionTypes] -- NOT redundant
 *
 * [allowedQuestionTypes] (default: all three non-OAuth [QuestionType]s) is enforced at two
 * different moments, for two different purposes:
 *
 * 1. **Schema/description shaping, at construction time** -- see [inputSchema] and
 *    [description] below, both computed eagerly from [allowedQuestionTypes]. They are built
 *    so that a disallowed form is, as much as
 *    possible, not even expressible by the LLM (e.g. [Input.allowCustomAnswer] is removed
 *    from the schema entirely when [QuestionType.OPEN_CHOICE] is forbidden). This layer
 *    *guides*: it saves a wasted LLM round-trip, but it guarantees nothing, because a schema
 *    is a suggestion to the LLM, not an enforced contract.
 * 2. **Validation in [execute]** -- the only layer that *guarantees*, because an LLM can and
 *    does violate declared schemas. [execute] derives the [QuestionType] exactly as before,
 *    then rejects it with a readable [ToolExecutionResult.error] if it is not in
 *    [allowedQuestionTypes], naming the forms that remain available so the LLM can retry
 *    correctly.
 *
 * Keeping both is deliberate: removing layer 1 would still be correct but would waste LLM
 * round-trips on predictably-rejected calls; removing layer 2 would leave the tool relying
 * on a schema the LLM is not guaranteed to respect.
 *
 * ## REJECT, do not COERCE
 *
 * When the derived [QuestionType] is not allowed, [execute] returns an error -- it never
 * silently downgrades e.g. an attempted [QuestionType.OPEN_CHOICE] into a
 * [QuestionType.SINGLE_CHOICE]. Coercing would make the agent believe the user can type a
 * free-text answer when the UI will not actually offer that field: the agent would narrate
 * an affordance that does not exist. Rejecting is the only safe behaviour, even though it
 * costs an extra round-trip; a future reader tempted to "simplify" this into a silent
 * downgrade would reintroduce that bug.
 *
 * ## Execution contract
 *
 * - When [input] is null or [Input.question] is blank, [execute] returns a
 *   human-readable [ToolExecutionResult.error] so the LLM can surface the problem
 *   gracefully without crashing the run.
 * - On the happy path, [execute] throws [AgentInterrupt.AwaitAnswer] -- a control-flow
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
 * @param allowedQuestionTypes The [QuestionType] forms this instance is allowed to produce.
 *   Defaults to all three forms the tool can produce ([QuestionType.OAUTH_AUTHORIZE] is never
 *   produced by this tool and is therefore never part of this set).
 */
class QueryUserTool(
    private val configName: String? = null,
    private val allowedQuestionTypes: Set<QuestionType> = QueryUserToolPlugin.DEFAULT_ALLOWED_QUESTION_TYPES,
) : StandardTool<QueryUserTool.Input> {
    /**
     * @param question The question text to display to the user.
     * @param options Optional list of answer choices. Null or empty -> free-text input.
     *   Non-empty -> the UI renders buttons. Combine with [allowCustomAnswer] to decide
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

    private val freeTextAllowed = QuestionType.FREE_TEXT in allowedQuestionTypes
    private val singleChoiceAllowed = QuestionType.SINGLE_CHOICE in allowedQuestionTypes
    private val openChoiceAllowed = QuestionType.OPEN_CHOICE in allowedQuestionTypes

    /** `options` becomes pointless once neither choice form can be selected. */
    private val optionsUseful = singleChoiceAllowed || openChoiceAllowed

    override val description: String =
        buildString {
            appendLine(
                """
                Ask the user a question. The run resumes automatically with the user's answer
                in the conversation history.

                IMPORTANT -- only use this tool when:
                - The information is genuinely required to proceed and cannot be inferred, guessed, or
                  found through any available tool or data source.
                - There is no reasonable default that the user could correct afterward.

                Do NOT use this tool:
                - to confirm destructive actions (use the built-in confirmation gate instead);
                - to be polite, to summarise what you are about to do, or to check in;
                - when you already have enough information to act.
                """.trimIndent(),
            )
            append(
                when {
                    !optionsUseful ->
                        "Only free-text questions are allowed in this configuration: do not provide options."
                    !freeTextAllowed && !openChoiceAllowed ->
                        "Only single-choice questions are allowed in this configuration: options are required " +
                            "and the user may not type a custom answer."
                    !freeTextAllowed ->
                        "Free-text-only questions are not allowed in this configuration: options are required. " +
                            "Set allowCustomAnswer=true if the user should also be able to type a custom answer " +
                            "beyond the listed options."
                    !openChoiceAllowed ->
                        "For multiple-choice questions, provide the options list. The user may not type a custom " +
                            "answer in this configuration: allowCustomAnswer is not available."
                    !singleChoiceAllowed ->
                        "When providing options, allowCustomAnswer must be true in this configuration: the user " +
                            "must always be able to type a custom answer in addition to the listed options."
                    else ->
                        "For multiple-choice questions, provide the options list. Set allowCustomAnswer=true " +
                            "if the user should also be able to type a custom answer beyond the listed options."
                },
            )
        }

    /**
     * Schema shaped from [allowedQuestionTypes] -- see class KDoc, layer 1. This only *guides*
     * the LLM; [execute] (layer 2) is what actually enforces the constraint.
     */
    override val inputSchema: String =
        buildString {
            append(
                """
                {
                  "type": "object",
                  "properties": {
                    "question": {
                      "type": "string",
                      "description": "The question to display to the user."
                    }
                """.trimIndent(),
            )
            if (optionsUseful) {
                val optionsDescription =
                    if (!freeTextAllowed) {
                        "List of answer choices rendered as buttons. Required in this configuration."
                    } else {
                        "Optional list of answer choices rendered as buttons. Omit for free-text input."
                    }
                append(
                    """
                    ,
                    "options": {
                      "type": "array",
                      "items": { "type": "string" },
                      "description": "$optionsDescription"
                    }
                    """.trimIndent(),
                )
                if (openChoiceAllowed) {
                    append(
                        """
                        ,
                        "allowCustomAnswer": {
                          "type": "boolean",
                          "description": "When true and options are provided, the user may also type a custom answer. Default: false.",
                          "default": false
                        }
                        """.trimIndent(),
                    )
                }
            }
            val required = if (optionsUseful && !freeTextAllowed) "\"question\", \"options\"" else "\"question\""
            append(
                """

                  },
                  "required": [$required]
                }
                """.trimIndent(),
            )
        }

    override val version: String = "1.0.0"
    override val paramType: Class<Input> = Input::class.java

    /**
     * Validates input, derives the [QuestionType], enforces [allowedQuestionTypes] (layer 2,
     * see class KDoc), and throws [AgentInterrupt.AwaitAnswer] to hand control back to the
     * orchestrator.
     *
     * Returns a human-readable [ToolExecutionResult.error] when [input] is null,
     * [Input.question] is blank, or the derived [QuestionType] is not allowed -- the LLM
     * receives this string and can surface it, or correct its call, without crashing the run.
     *
     * On the happy path, throws [AgentInterrupt.AwaitAnswer] -- this is a control-flow
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

        // REJECT, do not COERCE -- see class KDoc. Silently downgrading e.g. OPEN_CHOICE to
        // SINGLE_CHOICE would make the agent believe the user can type a custom answer when the
        // UI will not offer that field.
        if (questionType !in allowedQuestionTypes) {
            val allowedList = allowedQuestionTypes.joinToString(", ") { it.name }
            return ToolExecutionResult.error(
                "Question type $questionType is not allowed by this configuration. Allowed forms: $allowedList.",
                errorType = "QUESTION_TYPE_NOT_ALLOWED",
            )
        }

        // Normalise: pass null when there are no options so the QuestionEvent is clean.
        val options = input.options?.takeIf { it.isNotEmpty() }

        throw AgentInterrupt.AwaitAnswer(
            question = input.question,
            options = options,
            questionType = questionType,
            // userId identifies the user for whom the agent is running -- the one whose
            // answer is awaited. Null when the execution context has no resolved user
            // (webhook, system call, etc.). No fallback is applied: if it is null here,
            // the QuestionEvent remains addressed to any user of the case.
            userId = context.userId,
        )
    }
}
