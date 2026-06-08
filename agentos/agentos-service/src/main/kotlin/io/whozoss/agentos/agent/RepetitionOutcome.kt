package io.whozoss.agentos.agent

/**
 * Decision produced by [AgentAdvanced.handleRepetition] after comparing a
 * [ToolRepetition] count against the configured thresholds.
 *
 * - [Warned]: count just reached [AgentAdvanced.REPETITION_THRESHOLD] — a
 *   [io.whozoss.agentos.sdk.caseEvent.WarnEvent] has been emitted and [message]
 *   should be injected into the next intention prompt so the LLM can self-correct.
 * - [ForceStop]: count has strictly exceeded the threshold — the LLM ignored the
 *   earlier warning; the run loop must break immediately.
 */
sealed class RepetitionOutcome {
    abstract val message: String

    data class Warned(override val message: String) : RepetitionOutcome()

    data class ForceStop(override val message: String) : RepetitionOutcome()
}
