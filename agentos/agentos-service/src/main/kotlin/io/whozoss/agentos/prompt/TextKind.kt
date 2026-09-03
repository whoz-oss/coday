package io.whozoss.agentos.prompt

/**
 * Distinguishes the two kinds of text a [Prompt] exposes, so the LLM receives
 * accurate context about what it is translating.
 *
 * - [TITLE] — a short user-facing label shown on a button that starts a case.
 *   It summarises the prompt's purpose for the user, not for the agent.
 * - [CONTENT] — one instruction element sent to the agent when the case starts.
 *   It is directive in tone and may be technical or detailed.
 */
internal enum class TextKind(
    val context: String,
) {
    TITLE(
        "A prompt title is a short label shown on a button that users click to start a conversation with an AI agent.\n" +
            "It summarises the prompt's purpose for the user in a few words.",
    ),
    CONTENT(
        "A prompt content element is an instruction sent to an AI agent when a user starts a conversation.\n" +
            "It is directive in tone and tells the agent what to do.",
    ),
}
