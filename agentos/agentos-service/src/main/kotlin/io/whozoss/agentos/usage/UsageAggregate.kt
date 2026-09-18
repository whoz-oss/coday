package io.whozoss.agentos.usage

/**
 * Aggregated usage totals for a group of [UsageRecord]s.
 *
 * [cost] is the sum of all known costs and therefore a lower bound when
 * [unknownCostCount] is greater than zero. Unknown pricing is kept as separate
 * information rather than being converted to zero or invalidating the known sum.
 */
data class UsageAggregate(
    val recordCount: Long,
    val inputTokens: Long,
    val outputTokens: Long,
    val cacheReadTokens: Long,
    val cacheWriteTokens: Long,
    val totalTokens: Long,
    val cost: Double,
    val unknownCostCount: Long,
) {
    companion object {
        val EMPTY = UsageAggregate(
            recordCount = 0L,
            inputTokens = 0L,
            outputTokens = 0L,
            cacheReadTokens = 0L,
            cacheWriteTokens = 0L,
            totalTokens = 0L,
            cost = 0.0,
            unknownCostCount = 0L,
        )
    }
}

/** A single row in a grouped aggregation result (e.g. per-agent or per-model). */
data class UsageAggregateByKey(
    val key: String,
    val aggregate: UsageAggregate,
)

/** Cost lower bound and the amount of usage whose cost is still unknown. */
data class UsageCostAggregate(
    val cost: Double,
    val unknownCostCount: Long,
)
