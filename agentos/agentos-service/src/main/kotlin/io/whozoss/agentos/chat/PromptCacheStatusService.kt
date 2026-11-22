package io.whozoss.agentos.chat

import io.swagger.v3.oas.annotations.media.Schema
import io.whozoss.agentos.agent.LastLlmRequestRegistry
import io.whozoss.agentos.aiModel.AiModelService
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
 * Whether the provider's prompt cache likely still holds the conversation of a case, from the case's own last
 * request in the [LastLlmRequestRegistry] (sub-cases have their own): when the cache last saw its prefix
 * ([io.whozoss.agentos.agent.LastLlmRequest.lastUsedAt]: the final request of the last turn, or a later keep-alive
 * ping or side question) plus the cache lifetime of its model ([lifetimeOf]).
 *
 * The lifetime follows what the request was actually sent with: its recorded cache choice and request options say
 * whether the one-hour Requesty markers were applied ([ChatModelFactory.appliesOneHourCache]).
 */
@Service
class PromptCacheStatusService(
    private val registry: LastLlmRequestRegistry,
    private val aiModelService: AiModelService,
    private val chatModelFactory: ChatModelFactory,
    private val requestyPromptCache: RequestyPromptCacheProperties,
) {
    fun statusOf(caseId: UUID): PromptCacheStatus {
        val request = registry.get(caseId) ?: return PromptCacheStatus()
        val options = request.requestOptions
        val oneHour =
            chatModelFactory.appliesOneHourCache(
                options.apiType,
                options.baseUrl,
                options.apiModelName,
                request.promptCacheTtl,
            )
        val lifetime = lifetimeOf(options.apiModelName, oneHour) ?: return PromptCacheStatus()
        val pricing = aiModelService.findById(request.modelId)?.pricing
        return PromptCacheStatus(
            expiresAt = request.lastUsedAt.plus(lifetime),
            coldCostEstimate = coldCost(request.promptTokens, pricing, oneHour),
        )
    }

    /**
     * How long the provider keeps a prompt cached after its last use, by model family; null when unknown:
     * - Claude: 1 hour when the one-hour markers were applied, else the default 5 minutes. A model matching the
     *   Requesty pattern counts as Claude, as its markers must only reach Claude models.
     * - OpenAI GPT: 30 minutes, the minimum default retention of GPT-5.6 and later. Matched on the API model name,
     *   since Requesty policies are named after it (`policy/gpt-...`).
     */
    internal fun lifetimeOf(
        apiModelName: String,
        oneHourApplied: Boolean,
    ): Duration? =
        when {
            oneHourApplied -> ONE_HOUR
            CLAUDE.containsMatchIn(apiModelName) || requestyPromptCache.matchesModel(apiModelName) -> FIVE_MINUTES
            GPT.containsMatchIn(apiModelName) -> THIRTY_MINUTES
            else -> null
        }

    companion object {
        private val CLAUDE = Regex("(?i)claude")
        private val GPT = Regex("(?i)gpt")
        private val FIVE_MINUTES: Duration = Duration.ofMinutes(5)
        private val THIRTY_MINUTES: Duration = Duration.ofMinutes(30)
        private val ONE_HOUR: Duration = Duration.ofHours(1)

        /**
         * What writing [promptTokens] to the cache again costs: at the cache write rate (the input rate when the
         * model has none), or at twice the input rate for a one-hour cache (Anthropic's price). Null without a rate.
         */
        internal fun coldCost(
            promptTokens: Long?,
            pricing: ModelPricing?,
            oneHourApplied: Boolean,
        ): Double? {
            val tokens = promptTokens ?: return null
            val rate =
                if (oneHourApplied) pricing?.inputMTokens?.times(2) else pricing?.cacheWrite ?: pricing?.inputMTokens
            return rate?.let { tokens * it / 1_000_000 }
        }
    }
}
