package io.whozoss.agentos.chat

import io.swagger.v3.oas.annotations.media.Schema
import io.whozoss.agentos.agent.LastLlmRequestRegistry
import io.whozoss.agentos.agent.PromptCacheStamp
import io.whozoss.agentos.aiModel.AiModelService
import io.whozoss.agentos.sdk.aiProvider.AiApiType
import io.whozoss.agentos.sdk.aiProvider.ModelPricing
import org.springframework.stereotype.Service
import java.time.Duration
import java.time.Instant
import java.util.UUID

/** Response of `GET /api/cases/{caseId}/prompt-cache`. */
@Schema(name = "PromptCacheStatus")
data class PromptCacheStatus(
    @field:Schema(
        description =
            "When the provider's prompt cache of the case's last request expires; in the past once it is cold. " +
                "Null when unknown: no recent request of the case, or a model whose cache lifetime is unknown.",
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
 * lifetime of its model ([lifetimeOf]).
 *
 * The lifetime follows what the request was actually sent with: its recorded cache choice and request options say
 * whether the one-hour Requesty markers were applied ([ChatModelFactory.appliesOneHourCache]).
 */
@Service
class PromptCacheStatusService(
    private val registry: LastLlmRequestRegistry,
    private val aiModelService: AiModelService,
    private val chatModelFactory: ChatModelFactory,
    private val anthropicProperties: AnthropicProperties,
    private val requestyPromptCache: RequestyPromptCacheProperties,
) {
    fun statusOf(caseId: UUID): PromptCacheStatus {
        val stamp = registry.stamp(caseId) ?: return PromptCacheStatus()
        val oneHour =
            chatModelFactory.appliesOneHourCache(stamp.apiType, stamp.baseUrl, stamp.apiModelName, stamp.promptCacheTtl)
        val lifetime = lifetimeOf(stamp, oneHour) ?: return PromptCacheStatus()
        val pricing = aiModelService.findById(stamp.modelId)?.pricing
        return PromptCacheStatus(
            expiresAt = stamp.lastUsedAt.plus(lifetime),
            coldCostEstimate = coldCost(stamp.prefixTokens, pricing, oneHour),
        )
    }

    /**
     * How long the provider keeps a prompt cached after its last use; null when unknown:
     * - Claude: 1 hour when the one-hour markers were applied, else 5 minutes, but only where it is cached: the
     *   Anthropic API with [AnthropicProperties.promptCachingEnabled], or Requesty (automatic caching). A model
     *   matching the Requesty pattern counts as Claude, as its markers must only reach Claude models.
     * - OpenAI GPT: 30 minutes, the minimum default retention of GPT-5.6 and later ([GPT]).
     */
    internal fun lifetimeOf(
        stamp: PromptCacheStamp,
        oneHourApplied: Boolean,
    ): Duration? {
        val name = stamp.apiModelName
        val claude = CLAUDE.containsMatchIn(name) || requestyPromptCache.matchesModel(name)
        val cached =
            when (stamp.apiType) {
                AiApiType.Anthropic -> anthropicProperties.promptCachingEnabled
                AiApiType.OpenAI -> RequestyPromptCache.isRequesty(stamp.baseUrl)
                else -> false
            }
        return when {
            oneHourApplied -> ONE_HOUR
            claude -> FIVE_MINUTES.takeIf { cached }
            GPT.containsMatchIn(name) -> THIRTY_MINUTES
            else -> null
        }
    }

    companion object {
        private val CLAUDE = Regex("(?i)claude")

        /**
         * GPT-5.6 and later (`gpt-5.6`, `gpt-5.10`, `gpt-6.1-mini`, `openai/gpt-7`...), or a Requesty policy named
         * `gpt-<word>` (`policy/gpt-sol`), which carries no version. Older GPT models (`gpt-4o`, Azure's
         * `gpt-35-turbo`) and `gpt-oss` do not match.
         */
        private val GPT = Regex("(?i)gpt-(5\\.([6-9]|\\d{2,})|[6-9])|^policy/gpt-(?!oss)[a-z]")
        private val FIVE_MINUTES: Duration = Duration.ofMinutes(5)
        private val THIRTY_MINUTES: Duration = Duration.ofMinutes(30)
        private val ONE_HOUR: Duration = Duration.ofHours(1)

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
