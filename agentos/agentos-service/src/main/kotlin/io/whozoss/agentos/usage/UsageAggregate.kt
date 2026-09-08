package io.whozoss.agentos.usage

/**
 * Aggregated usage totals for a group of [UsageRecord]s.
 *
 * ## Null cost semantics
 *
 * [cost] is `null` when at least one record in the group has `cost == null` (pricing not
 * configured for the model at the time of the call). Summing across records with unknown
 * cost would produce a silent undercount; `null` is propagated instead, matching the
 * semantics of [io.whozoss.agentos.sdk.usage.LlmUsage.plus].
 *
 * Callers must never treat `null` as zero in further computations.
 *
 * All costs in AgentOS are expressed in a single implicit currency unit — there is no
 * multi-currency dimension to group or guard against.
 */
data class UsageAggregate(
    val recordCount: Long,
    val inputTokens: Long,
    val outputTokens: Long,
    val cacheReadTokens: Long,
    val cacheWriteTokens: Long,
    val totalTokens: Long,
    /**
     * Summed cost for all records in this aggregate, or `null` when at least one
     * contributing record had no pricing configured (cost unknown, not zero).
     */
    val cost: Double?,
) {
    companion object {
        val EMPTY = UsageAggregate(
            recordCount = 0L,
            inputTokens = 0L,
            outputTokens = 0L,
            cacheReadTokens = 0L,
            cacheWriteTokens = 0L,
            totalTokens = 0L,
            cost = null,
        )
    }
}

/**
 * A single row in a grouped aggregation result (e.g. per-agent or per-model).
 *
 * [key] is the group dimension value (agent name, model name, etc.).
 */
data class UsageAggregateByKey(
    val key: String,
    val aggregate: UsageAggregate,
)
