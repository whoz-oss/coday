package io.whozoss.agentos.chat

import io.swagger.v3.oas.annotations.media.Schema
import io.whozoss.agentos.agent.LastLlmRequestRegistry
import io.whozoss.agentos.agent.PromptCacheStamp
import io.whozoss.agentos.aiModel.AiModelService
import io.whozoss.agentos.sdk.aiProvider.ModelPricing
import org.springframework.stereotype.Service
import java.time.Instant
import java.util.UUID

/** Response of `GET /api/cases/{caseId}/prompt-cache`. */
@Schema(name = "PromptCacheStatus")
data class PromptCacheStatus(
    @field:Schema(
        description =
            "When the provider's prompt cache of the case's last request expires; in the past once it is cold. " +
                "Null when unknown: no recent request of the case, or no cache TTL on its model.",
    )
    val expiresAt: Instant? = null,
    @field:Schema(
        description =
            "Estimated cost of writing the prompt of the last request to the cache again, in the platform " +
                "currency. Null when unknown (no pricing for the model, or no recorded prompt size).",
    )
    val coldCostEstimate: Double? = null,
)

/**
 * Whether the provider's prompt cache likely still holds the conversation of a case, from the [PromptCacheStamp] of
 * its own last captured request ([LastLlmRequestRegistry.stamp]; sub-cases have their own): when the cache last saw
 * its prefix (the final request of the last turn, or a later keep-alive ping or side question) plus the cache
 * lifetime configured on its model ([PromptCacheLifetime]).
 *
 * Whether the one-hour Requesty markers were applied follows what the request was actually sent with: its recorded
 * cache choice and request options ([ChatModelFactory.appliesOneHourCache]).
 */
@Service
class PromptCacheStatusService(
    private val registry: LastLlmRequestRegistry,
    private val aiModelService: AiModelService,
    private val chatModelFactory: ChatModelFactory,
) {
    fun statusOf(caseId: UUID): PromptCacheStatus {
        val stamp = registry.stamp(caseId) ?: return PromptCacheStatus()
        val oneHour =
            chatModelFactory.appliesOneHourCache(stamp.apiType, stamp.baseUrl, stamp.apiModelName, stamp.promptCacheTtl)
        val model = aiModelService.findById(stamp.modelId)
        val lifetime = PromptCacheLifetime.of(model, oneHour) ?: return PromptCacheStatus()
        return PromptCacheStatus(
            expiresAt = stamp.lastUsedAt.plus(lifetime),
            coldCostEstimate = coldCost(stamp.prefixTokens, model?.pricing, oneHour),
        )
    }

    companion object {
        /**
         * What writing [prefixTokens] (the last prompt and its answer) to the cache again costs: at the cache write
         * rate (the input rate when the model has none), or at twice the input rate for a one-hour cache
         * (Anthropic's price). Null without a rate.
         */
        internal fun coldCost(
            prefixTokens: Long?,
            pricing: ModelPricing?,
            oneHourApplied: Boolean,
        ): Double? {
            val tokens = prefixTokens ?: return null
            val rate =
                if (oneHourApplied) pricing?.inputMTokens?.times(2) else pricing?.cacheWrite ?: pricing?.inputMTokens
            return rate?.let { tokens * it / 1_000_000 }
        }
    }
}
