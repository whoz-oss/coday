package io.whozoss.agentos.agent

import io.whozoss.agentos.sdk.caseEvent.QuestionType
import java.util.UUID

/**
 * Sealed exception hierarchy used as a control-flow signal to interrupt the current
 * agent run from inside a [io.whozoss.agentos.sdk.tool.StandardTool] execution.
 *
 * ## Why exceptions?
 *
 * [io.whozoss.agentos.sdk.tool.StandardTool] is called through Spring AI's
 * [org.springframework.ai.tool.ToolCallback] interface, whose `call` contract returns
 * a plain `String`. There is no way to signal an out-of-band interruption via a return
 * value without leaking control-flow semantics into the tool result string. Throwing an
 * exception is the only mechanism that exits `ToolCallback.call` without returning a
 * result and propagates cleanly through Spring AI's internal tool-calling loop up to the
 * [AgentSimple] or [AgentAdvanced] catch block.
 *
 * ## Exhaustiveness
 *
 * Because this is a sealed class, every `when` on [AgentInterrupt] is checked
 * exhaustively by the Kotlin compiler. Adding a new interrupt type without handling it
 * is a compile error.
 *
 * ## Current members
 *
 * - [Redirect]: hand off the current case to another agent.
 * - [AwaitAnswer]: terminate the current run and wait for the user to answer a question.
 *   The run resumes automatically once the answer is received (pre-flight in [CaseRuntime]).
 *
 * ## Planned members
 *
 * - `SyncDelegation`: suspend the current agent and wait for a sub-case to finish.
 */
sealed class AgentInterrupt(
    message: String,
) : RuntimeException(message) {
    /**
     * Request a hand-off to another agent.
     *
     * Thrown by [io.whozoss.agentos.redirect.RedirectTool] after recording the intent.
     * [AgentSimple] catches this and emits
     * [io.whozoss.agentos.sdk.caseEvent.AgentSelectedEvent] +
     * [io.whozoss.agentos.sdk.caseEvent.AgentFinishedEvent] to trigger the next agent.
     *
     * @param targetAgentName The exact name of the agent to redirect to, as it appears
     *   in the [io.whozoss.agentos.agentConfig.AgentConfig] of the namespace.
     */
    class Redirect(
        val targetAgentName: String,
    ) : AgentInterrupt("Redirect to '\$targetAgentName'")

    /**
     * Terminate the current agent run and emit a [io.whozoss.agentos.sdk.caseEvent.QuestionEvent]
     * so the user can answer asynchronously.
     *
     * Thrown by [io.whozoss.agentos.queryUser.QueryUserTool] on the happy path.
     * [AgentInterruptHandler.emitInterruptAndFinishEvents] catches this, emits
     * [io.whozoss.agentos.sdk.caseEvent.AgentFinishedEvent] to close the turn, then
     * emits a [io.whozoss.agentos.sdk.caseEvent.QuestionEvent] addressed to the user
     * identified by [userId] (or any user when [userId] is null).
     *
     * The run resumes automatically: [io.whozoss.agentos.caseFlow.CaseRuntime.run]
     * performs a pre-flight check ([CaseRuntime.findUnresolvedQuestion]) at the start
     * of each turn. When it finds a [io.whozoss.agentos.sdk.caseEvent.QuestionEvent]
     * that has been answered but whose agent has not yet restarted, it emits an
     * [io.whozoss.agentos.sdk.caseEvent.AgentSelectedEvent] targeting the original
     * agent so the normal loop picks up from there.
     *
     * @param question The question text to display to the user.
     * @param options Optional list of choices. Null or empty → [QuestionType.FREE_TEXT].
     *   Non-empty + [allowCustomAnswer]=false → [QuestionType.SINGLE_CHOICE].
     *   Non-empty + [allowCustomAnswer]=true → [QuestionType.OPEN_CHOICE].
     * @param questionType The resolved [QuestionType], derived by [QueryUserTool] from
     *   [options] and [allowCustomAnswer] before throwing.
     * @param userId The user for whom the agent is running — the one whose answer is
     *   awaited. Null means the question is addressed to any user of the case (e.g. when
     *   the executing context has no resolved user, such as a webhook or system call).
     *   No artificial fallback is applied: if the upstream context has no userId, this
     *   stays null and the [io.whozoss.agentos.sdk.caseEvent.QuestionEvent] remains
     *   unaddressed.
     */
    class AwaitAnswer(
        val question: String,
        val options: List<String>? = null,
        val questionType: QuestionType = QuestionType.FREE_TEXT,
        val userId: UUID? = null,
    ) : AgentInterrupt("Awaiting user answer")
}
