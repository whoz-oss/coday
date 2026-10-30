package io.whozoss.agentos.sdk.api.usageRecord

import io.swagger.v3.oas.annotations.media.Schema

/** Aggregated usage totals returned by the analytics endpoints. */
@Schema(name = "UsageAggregate")
data class UsageAggregateDto(
    val recordCount: Long,
    val inputTokens: Long,
    val outputTokens: Long,
    val cacheReadTokens: Long,
    val cacheWriteTokens: Long,
    val totalTokens: Long,
    /** Sum of known costs; a lower bound when [unknownCostCount] is positive. */
    val cost: Double,
    /** Number of contributing records for which pricing is unknown. */
    val unknownCostCount: Long,
)

/** One row of a grouped aggregation (e.g. per-agent or per-model). */
@Schema(name = "UsageAggregateByKey")
data class UsageAggregateByKeyDto(
    val key: String,
    val aggregate: UsageAggregateDto,
)
