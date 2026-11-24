package io.whozoss.agentos.chat

import io.whozoss.agentos.sdk.aiProvider.AiModel
import java.time.Duration

/**
 * How long the provider keeps a request's prompt cached after its last use, as configured on its model: the one rule
 * of the prompt cache status of a case ([PromptCacheStatusService]) and of its keep-alive
 * ([io.whozoss.agentos.caseFlow.CaseKeepAliveScheduler]). [AiModel.extendedCacheTtlMinutes] when the request got the
 * one-hour cache markers ([ChatModelFactory.appliesOneHourCache], which alone decides whether they are sent), else
 * [AiModel.cacheTtlMinutes]; null when that field is empty or not positive, or the model is gone.
 */
object PromptCacheLifetime {
    fun of(
        model: AiModel?,
        oneHourMarkersApplied: Boolean,
    ): Duration? {
        val minutes = if (oneHourMarkersApplied) model?.extendedCacheTtlMinutes else model?.cacheTtlMinutes
        return minutes?.takeIf { it > 0 }?.let { Duration.ofMinutes(it.toLong()) }
    }
}
