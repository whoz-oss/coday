package io.whozoss.agentos.chat

import io.whozoss.agentos.sdk.aiProvider.AiModel
import java.time.Duration

/**
 * How long the provider keeps a request's prompt cached after its last use, read from the configuration of its
 * model: the one rule of the prompt cache status of a case ([PromptCacheStatusService]) and of its keep-alive
 * ([io.whozoss.agentos.caseFlow.CaseKeepAliveScheduler]).
 *
 * - The request got the one-hour cache markers ([ChatModelFactory.appliesOneHourCache], which alone decides whether
 *   they are sent): [AiModel.extendedCacheTtlMinutes], else [ONE_HOUR_MARKERS_MINUTES], the TTL the markers ask for.
 * - Otherwise: [AiModel.cacheTtlMinutes], else [DEFAULT_MINUTES].
 *
 * A missing [model] (deleted since) or a value that is not positive falls back to the default.
 */
object PromptCacheLifetime {
    const val DEFAULT_MINUTES = 5
    const val ONE_HOUR_MARKERS_MINUTES = 60

    fun of(
        model: AiModel?,
        oneHourMarkersApplied: Boolean,
    ): Duration {
        val minutes =
            if (oneHourMarkersApplied) {
                model?.extendedCacheTtlMinutes.positiveOr(ONE_HOUR_MARKERS_MINUTES)
            } else {
                model?.cacheTtlMinutes.positiveOr(DEFAULT_MINUTES)
            }
        return Duration.ofMinutes(minutes.toLong())
    }

    private fun Int?.positiveOr(default: Int): Int = this?.takeIf { it > 0 } ?: default
}
