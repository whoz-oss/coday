package io.whozoss.agentos.usage

/**
 * Terminal outcome of the agent turn that produced a [UsageRecord].
 *
 * - [COMPLETED] — the model replied normally and the turn finished without errors.
 * - [FAILED]    — the model call threw an exception or the provider returned an error.
 * - [INTERRUPTED] — the case was killed while the model was still generating.
 */
enum class UsageOutcome {
    COMPLETED,
    FAILED,
    INTERRUPTED,
}
