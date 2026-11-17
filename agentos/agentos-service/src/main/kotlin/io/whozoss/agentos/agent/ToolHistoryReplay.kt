package io.whozoss.agentos.agent

/**
 * How the tool calls and results of PREVIOUS turns are replayed into an agent's LLM context.
 *
 * Only the conversion of case events into Spring AI messages is affected: the
 * [io.whozoss.agentos.sdk.caseEvent.ToolRequestEvent] / [io.whozoss.agentos.sdk.caseEvent.ToolResponseEvent]
 * stay persisted, streamed and displayed as before, and `ToolContext.caseEvents` handed to the tools is unchanged.
 *
 * A turn starts at a user message. A tool exchange is "past" when its request precedes the last user
 * [io.whozoss.agentos.sdk.caseEvent.MessageEvent] of the case; the exchanges after it (the current turn) are
 * always replayed in full, whatever the mode. An answer to a question the agent asked mid-task is not a turn
 * boundary: the agent still needs its earlier results to continue. With no user message, nothing is past.
 * The decision depends only on the events, so the prompt of a given turn stays byte-stable and the provider's
 * prompt cache prefix keeps hitting within that turn.
 *
 * Bound from `agentos.defaults.tool-history-replay` (environment variable `AGENTOS_DEFAULTS_TOOL_HISTORY_REPLAY`).
 */
enum class ToolHistoryReplay {
    /** Past tool calls and their results are replayed as they are today. Default. */
    FULL,

    /**
     * Past tool exchanges keep the tool call (name and arguments), but the result text is replaced by a constant
     * placeholder and their images are never attached.
     *
     * The recommended lightweight mode: keeping the call, with its arguments, tells the model what it already did
     * and prevents it from running a side-effecting action again (create an issue, send a message...), while the
     * results, which are the bulk of the tokens and may be stale by the next turn, are dropped.
     */
    CALLS_ONLY,

    /**
     * Past tool exchanges are removed entirely, call and result, images included.
     *
     * Only safe for agents whose tools are read-only: the model no longer sees that it already performed an
     * action, so it may perform it again.
     */
    NONE,
}
