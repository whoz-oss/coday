package io.whozoss.agentos.sdk.api.usageRecord

import java.time.Instant
import java.util.UUID

/** Known cost lower bound since the latest user message, including delegated/live work. */
data class RunCostDto(
    val caseId: UUID,
    val since: Instant,
    val cost: Double,
    val unknownCostCount: Long,
    val runCostThreshold: Double?,
    val paused: Boolean,
    val active: Boolean,
    val liveTokens: Long,
    val pausedCases: List<PausedCostDto> = emptyList(),
)

data class PausedCostDto(
    val caseId: UUID,
    val cost: Double,
    val threshold: Double,
)

data class ContinueCostRequest(
    val expectedThreshold: Double,
)

interface RunCostApi {
    fun getRunCost(caseId: UUID): RunCostDto

    fun continueCostRun(
        caseId: UUID,
        request: ContinueCostRequest,
    ): RunCostDto

    fun stopCostRun(caseId: UUID)
}
