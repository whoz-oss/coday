package io.whozoss.agentos.agent

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
 * - [AwaitConfirmation]: pause until the user confirms or rejects a pending tool action.
 *
 * ## Planned members
 *
 * - `SyncDelegation`: suspend the current agent and wait for a sub-case to finish.
 * - `AwaitAnswer`: suspend and wait for a human answer to a QuestionEvent.
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
     * Pause the current agent run waiting for user confirmation of a tool action
     * (WZ-31596).
     *
     * Thrown by the tool callback in [AgentSimple] when a tool with
     * `supportsConfirmation = true` declares it needs explicit confirmation and the
     * [ConfirmationManager.shouldConfirm] LLM check confirms the user hasn't already
     * implicitly agreed.
     *
     * The interrupt handler emits — in order — a [io.whozoss.agentos.sdk.caseEvent.PendingConfirmationEvent]
     * (durable orchestration marker), a [io.whozoss.agentos.sdk.caseEvent.QuestionEvent]
     * (the user-facing prompt, out-of-LLM-channel — the LLM never sees this), and then an
     * [io.whozoss.agentos.sdk.caseEvent.AgentFinishedEvent] to close the turn cleanly,
     * letting the user reply at any future point (CA6).
     *
     * Crucially, the throw exits the Spring AI tool loop **before** a tool_result is
     * appended to the LLM history. The next turn's `convertEventsToMessages` will inject
     * a synthetic tool_result for the original tool_use once the pending is resolved,
     * keeping the LLM history clean (Hermes-style out-of-channel approval, but async).
     *
     * @param toolName Qualified tool name (e.g. `FILES__remove`).
     * @param toolRequestId Id of the [io.whozoss.agentos.sdk.caseEvent.ToolRequestEvent]
     *   that initiated this pending. Used to pair with the synthetic tool_result later.
     * @param pendingPayloadJson Payload serialised as JSON (string-typed for
     *   plugin/service classloader safety).
     * @param confirmationLabel Sanitized deterministic label (whitelist + 200 chars).
     *   Persisted on the [PendingConfirmationEvent] for audit; used as fallback if the
     *   LLM-formulated [question] is unavailable.
     * @param question User-facing prompt formulated by the LLM out-of-channel (matches the
     *   conversation language/register). Becomes the text of the [QuestionEvent].
     * @param analysisInstructions Optional plugin-supplied analyze-confirmation guidance,
     *   used as fallback when the user replies in free-form text (no AnswerEvent).
     * @param questionId Pre-generated id for the paired QuestionEvent. The handler uses
     *   this id when emitting the event so [PendingConfirmationEvent.questionId] and the
     *   QuestionEvent point at each other.
     */
    class AwaitConfirmation(
        val toolName: String,
        val toolRequestId: String,
        val pendingPayloadJson: String,
        val confirmationLabel: String,
        val question: String,
        val analysisInstructions: String,
        val questionId: UUID,
    ) : AgentInterrupt("Awaiting user confirmation for '$toolName'")
}
