package io.whozoss.agentos.sdk.api.usageRecord

/** Lifetime union of the requested case trees, including in-flight agent usage. USD known lower bound. */
data class CumulativeUsageDto(
    val cost: Double,
    val unknownCostCount: Long,
    val totalTokens: Long,
    val inputTokens: Long,
    val outputTokens: Long,
    val cacheReadTokens: Long,
    val cacheWriteTokens: Long,
    /** Subset of totalTokens still in active accumulators. */
    val liveTokens: Long = 0,
)
