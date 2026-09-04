package io.whozoss.agentos.usage

import com.fasterxml.jackson.annotation.JsonIgnoreProperties
import io.whozoss.agentos.sdk.entity.Entity
import io.whozoss.agentos.sdk.entity.EntityMetadata
import io.whozoss.agentos.sdk.usage.LlmUsage
import java.time.Instant
import java.util.UUID

/**
 * Immutable analytical record capturing the cost and token usage of a single agent turn.
 *
 * ## Semantics of [cost]
 * `null` means the cost is **unknown** — the pricing for the model was not configured at
 * the time of the call. It does NOT mean zero cost. Callers must never treat `null` as `0.0`
 * in aggregations; instead they must propagate the unknown upward (e.g. sum returns null if
 * any addend is null, matching the semantics of [LlmUsage.plus]).
 *
 * ## Currency and cross-record aggregation
 * [currency] is always stored alongside [cost]. Summing costs across records without grouping
 * by currency would silently mix amounts in different units. Any aggregation query **must**
 * include a `GROUP BY currency` (or equivalent) clause.
 *
 * ## Denormalised provider and model names
 * [providerName] and [apiModelName] are stored as plain strings rather than foreign keys.
 * This mirrors the "invoice line" principle: a fact recorded at time T must reflect the
 * configuration that was active at time T. The pricing on [io.whozoss.agentos.sdk.aiProvider.AiModel]
 * is mutable; a future price change must not silently alter historical cost records.
 *
 * ## Token fields
 * The five token counters are stored flat (not as a nested [LlmUsage]) so that [UsageRecord]
 * can later carry non-LLM costs (e.g. image generation) where the token counters have no
 * meaning. Use [companion.fromLlmUsage] to construct a record from a [LlmUsage] value object
 * without manually copying each field.
 */
@JsonIgnoreProperties(ignoreUnknown = true)
data class UsageRecord(
    override val metadata: EntityMetadata = EntityMetadata(),
    val namespaceId: UUID,
    val caseId: UUID,
    val userId: UUID? = null,
    val source: UsageSource,
    val outcome: UsageOutcome,
    val agentConfigId: UUID? = null,
    val agentName: String,
    val providerName: String? = null,
    val apiModelName: String? = null,
    val inputTokens: Long = 0L,
    val outputTokens: Long = 0L,
    val cacheReadTokens: Long = 0L,
    val cacheWriteTokens: Long = 0L,
    val totalTokens: Long = 0L,
    /** `null` = cost unknown (pricing not configured), not zero. See class KDoc. */
    val cost: Double? = null,
    /** ISO 4217 currency code. Costs in different currencies must never be summed without grouping. */
    val currency: String = "USD",
    val timestamp: Instant = Instant.now(),
) : Entity {
    companion object {
        /**
         * Convenience factory that copies the five token counters and the estimated cost
         * from a [LlmUsage] value object into a new [UsageRecord].
         *
         * The [LlmUsage.estimatedCostUsd] is mapped to [cost]; [currency] defaults to `"USD"`
         * because [LlmUsage] always expresses cost in USD.
         */
        fun fromLlmUsage(
            llmUsage: LlmUsage,
            namespaceId: UUID,
            caseId: UUID,
            agentName: String,
            outcome: UsageOutcome,
            userId: UUID? = null,
            agentConfigId: UUID? = null,
            providerName: String? = null,
            apiModelName: String? = null,
        ): UsageRecord =
            UsageRecord(
                namespaceId = namespaceId,
                caseId = caseId,
                userId = userId,
                source = UsageSource.LLM,
                outcome = outcome,
                agentConfigId = agentConfigId,
                agentName = agentName,
                providerName = providerName,
                apiModelName = apiModelName,
                inputTokens = llmUsage.inputTokens,
                outputTokens = llmUsage.outputTokens,
                cacheReadTokens = llmUsage.cacheReadTokens,
                cacheWriteTokens = llmUsage.cacheWriteTokens,
                totalTokens = llmUsage.totalTokens,
                cost = llmUsage.estimatedCostUsd,
                currency = "USD",
            )
    }
}
