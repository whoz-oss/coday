package io.whozoss.agentos.sdk.api.usageRecord

import io.swagger.v3.oas.annotations.media.Schema

/**
 * Aggregated usage totals returned by the analytics endpoints.
 *
 * [cost] is `null` when at least one contributing record had no pricing configured.
 * All costs share a single implicit currency unit (USD).
 */
@Schema(name = "UsageAggregate")
data class UsageAggregateDto(
    val recordCount: Long,
    val inputTokens: Long,
    val outputTokens: Long,
    val cacheReadTokens: Long,
    val cacheWriteTokens: Long,
    val totalTokens: Long,
    /** null = at least one record had unknown cost. */
    @Schema(nullable = true)
    @field:Schema(types = ["number", "null"], format = "double")
    val cost: Double?,
)

/**
 * One row of a grouped aggregation (e.g. per-agent or per-model).
 */
@Schema(name = "UsageAggregateByKey")
data class UsageAggregateByKeyDto(
    val key: String,
    val aggregate: UsageAggregateDto,
)
