package io.whozoss.agentos.usage

/**
 * Aggregated usage totals for a group of [UsageRecord]s.
 *
 * ## Null cost semantics
 *
 * [costByCurrency] maps each currency code to its summed cost, or to `null` when at least
 * one record in that currency group has `cost == null` (pricing not configured).
 * Summing across records with unknown cost would produce a silent undercount; `null` is
 * propagated instead, matching the semantics of [io.whozoss.agentos.sdk.usage.LlmUsage.plus].
 *
 * Callers must never treat `null` as zero in further computations.
 *
 * ## Multi-currency safety
 *
 * Costs in different currencies are never summed together. Each currency is a separate
 * entry in [costByCurrency]. A single-currency deployment (e.g. all USD) will always
 * produce a single-entry map.
 */
data class UsageAggregate(
    val recordCount: Long,
    val inputTokens: Long,
    val outputTokens: Long,
    val cacheReadTokens: Long,
    val cacheWriteTokens: Long,
    val totalTokens: Long,
    /**
     * Cost summed per currency. `null` value means the cost for that currency is unknown
     * because at least one contributing record had no pricing configured.
     */
    val costByCurrency: Map<String, Double?>,
) {
    companion object {
        val EMPTY = UsageAggregate(
            recordCount = 0L,
            inputTokens = 0L,
            outputTokens = 0L,
            cacheReadTokens = 0L,
            cacheWriteTokens = 0L,
            totalTokens = 0L,
            costByCurrency = emptyMap(),
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
