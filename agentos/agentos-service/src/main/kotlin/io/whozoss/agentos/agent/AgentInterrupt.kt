package io.whozoss.agentos.agent

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
     * Thrown by the tool callback in [AgentSimple]/[AgentAdvanced] when a tool with
     * `supportsConfirmation = true` declares it needs explicit confirmation and the
     * [ConfirmationManager.shouldConfirm] LLM check confirms the user hasn't already
     * implicitly agreed.
     *
     * The interrupt handler emits a [io.whozoss.agentos.sdk.caseEvent.PendingConfirmationEvent]
     * carrying the payload to confirm, then an [io.whozoss.agentos.sdk.caseEvent.AgentFinishedEvent]
     * to close the turn cleanly — letting the user reply at any future point (CA6).
     *
     * @param toolName Qualified tool name (e.g. `FILES__remove`).
     * @param toolRequestId Id of the [io.whozoss.agentos.sdk.caseEvent.ToolRequestEvent]
     *   that initiated this pending — kept for traceability with the LLM-visible cycle.
     * @param pendingPayloadJson Payload serialised as JSON (string-typed for
     *   plugin/service classloader safety).
     * @param confirmationLabel Sanitized human-readable label (whitelist + 200 chars)
     *   suitable for inclusion in an LLM prompt and a UI message.
     * @param analysisInstructions Optional plugin-supplied analyze-confirmation guidance.
     */
    class AwaitConfirmation(
        val toolName: String,
        val toolRequestId: String,
        val pendingPayloadJson: String,
        val confirmationLabel: String,
        val analysisInstructions: String,
    ) : AgentInterrupt("Awaiting user confirmation for '$toolName'")
}
