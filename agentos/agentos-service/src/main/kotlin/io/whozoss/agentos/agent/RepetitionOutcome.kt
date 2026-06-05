package io.whozoss.agentos.agent

/**
 * Result of repetition detection in [AgentAdvanced.handleRepetitionDetection].
 *
 * - [Warned]: first time the threshold is crossed — a [io.whozoss.agentos.sdk.caseEvent.WarnEvent]
 *   has been emitted and [message] is injected into the next intention prompt so the LLM
 *   can self-correct.
 * - [ForceStop]: the threshold was crossed again after the warning was already emitted.
 *   The LLM ignored the warning; the run loop must be terminated immediately.
 */
sealed class RepetitionOutcome {
    abstract val message: String

    data class Warned(override val message: String) : RepetitionOutcome()

    data class ForceStop(override val message: String) : RepetitionOutcome()
}
