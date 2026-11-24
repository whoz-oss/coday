package io.whozoss.factory.proxy

/** Boundary view of AgentOS lifetime case-tree union analytics. */
data class CumulativeUsageDto(
    val cost: Double,
    val unknownCostCount: Long,
    val totalTokens: Long,
    val inputTokens: Long,
    val outputTokens: Long,
    val cacheReadTokens: Long,
    val cacheWriteTokens: Long,
    val liveTokens: Long? = null,
)
