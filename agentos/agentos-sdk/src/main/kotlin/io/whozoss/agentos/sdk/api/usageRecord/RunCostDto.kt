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
    /** This case's next request is waiting on its own threshold or an ancestor's threshold. */
    val paused: Boolean,
    val active: Boolean,
    val liveTokens: Long,
    /** Readable pending limits for this case, its delegated work and its blocking ancestors. */
    val pausedCases: List<PausedCostDto> = emptyList(),
)

data class PausedCostDto(
    val caseId: UUID,
    val cost: Double,
    val threshold: Double,
    /** The limit belongs to an ancestor of the requested case. */
    val ancestor: Boolean = false,
    /** The caller can confirm this specific case's limit; the write endpoint checks again. */
    val canContinue: Boolean = false,
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
