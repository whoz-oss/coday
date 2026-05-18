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
}
