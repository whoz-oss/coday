package io.whozoss.agentos.scheduledPrompt

import java.time.Instant
import java.util.UUID

/**
 * Repository for [ScheduledPromptRun] persistence.
 *
 * Intentionally does not extend [io.whozoss.agentos.entity.EntityRepository] because runs are
 * append-only audit records, not editable entities. Only [insert] and [hasActive] are needed
 * by the scheduler engine.
 */
interface ScheduledPromptRunRepository {

    /**
     * Persist a new [ScheduledPromptRun].
     *
     * @throws DuplicateRunException if a run for the same `(scheduledPromptId, scheduledFor)` slot
     *   already exists (UNIQUE constraint on [ScheduledPromptRunNode.slotKey]).
     */
    fun insert(run: ScheduledPromptRun): ScheduledPromptRun

    /**
     * Returns true if there is at least one run for [scheduledPromptId] in an active status
     * (CLAIMED or RUNNING). Used to detect overlapping executions.
     */
    fun hasActive(scheduledPromptId: UUID): Boolean

    /**
     * Update the status of a Run, optionally setting [finishedAt] and [error].
     *
     * Returns true if the update was applied (the Run existed and was not already in the
     * target status), false otherwise.
     */
    fun updateStatus(
        id: UUID,
        status: RunStatus,
        finishedAt: Instant? = null,
        error: String? = null,
    ): Boolean

    /** Find a single Run by its id, or null if not found. */
    fun findById(id: UUID): ScheduledPromptRun?

    /**
     * Count completed (non-SKIPPED) runs for a given ScheduledPrompt.
     * Counts DONE, FAILED, CLAIMED, and RUNNING — everything except SKIPPED,
     * since SKIPPED runs are overlap-guards that never actually executed.
     */
    fun countCompletedRuns(scheduledPromptId: UUID): Int

    /**
     * Find all Runs in CLAIMED status created before [olderThan].
     *
     * These are presumed orphaned — the instance that inserted them crashed before
     * calling [ScheduledPromptExecutor.materialize]. The caller marks them FAILED
     * to unblock the overlap guard and leave a diagnostic trace.
     */
    fun findOrphanedClaimed(olderThan: Instant): List<ScheduledPromptRun>

    /**
     * Find RUNNING Runs whose UserRuns are ALL in a terminal status (DONE or FAILED).
     *
     * A Run is "settled" when none of its UserRuns are in PENDING or RUNNING status.
     * Runs with zero UserRuns (platform-scope or no target users) are directly transitioned
     * to DONE in [ScheduledPromptExecutor.materialize] and never reach RUNNING status.
     *
     * These Runs are presumed orphaned — the instance that processed the last UserRun
     * crashed after [ScheduledPromptUserRunRepository.markTerminal] but before
     * [ScheduledPromptExecutor]'s `checkCompletion()` could transition the parent Run.
     */
    fun findSettledRunning(): List<ScheduledPromptRun>
}
