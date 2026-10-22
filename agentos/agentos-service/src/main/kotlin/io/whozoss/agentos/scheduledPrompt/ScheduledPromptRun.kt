package io.whozoss.agentos.scheduledPrompt

import io.whozoss.agentos.sdk.entity.Entity
import io.whozoss.agentos.sdk.entity.EntityMetadata
import java.time.Instant
import java.util.UUID

/** Execution status of a single [ScheduledPromptRun]. */
enum class RunStatus {
    /** The run has been claimed by the scheduler; execution is imminent. */
    CLAIMED,
    /**
     * Converged path: the loop case has been created and is executing.
     * No [ScheduledPromptUserRun] rows exist for runs in this status.
     *
     * TARGETING is intentionally excluded from [ScheduledPromptRunNodeNeo4jRepository.findSettledRunning],
     * which matches `status = 'RUNNING'` with no PENDING/RUNNING UserRun. Since the converged
     * path writes NO UserRun rows, adding TARGETING to that query would close every live
     * converged run as DONE within ~30s. Orphaned TARGETING runs are swept separately
     * by [SchedulerScanner.recoverOrphanedTargetingRuns].
     */
    TARGETING,
    /** The run was skipped (misfire or overlap). */
    SKIPPED,
    /**
     * Legacy path only. Never written by converged code; retained so old nodes deserialize.
     * The agent is currently executing the run via per-user UserRun rows.
     */
    RUNNING,
    /** The run completed successfully. */
    DONE,
    /** The run failed; see [ScheduledPromptRun.error] for details. */
    FAILED,
}

/**
 * Represents a single execution attempt of a [ScheduledPrompt].
 *
 * One [ScheduledPromptRun] is created per scheduler tick that processes a given prompt.
 * A composite UNIQUE constraint on `(scheduledPromptId, scheduledFor)` in Neo4j acts as an
 * optimistic distributed lock preventing duplicate firings for the same slot.
 *
 * ### Lifecycle
 *
 * Converged path: CLAIMED → TARGETING → DONE | FAILED
 * Legacy path:     CLAIMED → RUNNING → DONE (happy path)
 *                                    → FAILED (execution error)
 * SKIPPED (misfire or overlap, decided before claiming)
 *
 * [attempt] starts at 0 for the first try; future retry logic may increment it.
 * [finishedAt] and [error] are populated when the run reaches a terminal state.
 * [correlationId] is a short human-readable tag for log correlation.
 */
data class ScheduledPromptRun(
    override val metadata: EntityMetadata = EntityMetadata(),
    val scheduledPromptId: UUID,
    /** The UTC instant for which this slot was scheduled. */
    val scheduledFor: Instant,
    val status: RunStatus,
    /** Short correlation tag for log tracing (e.g. "sp-<uuid-prefix>-<epoch-second>"). */
    val correlationId: String,
    val attempt: Int = 0,
    val finishedAt: Instant? = null,
    val error: String? = null,
) : Entity
