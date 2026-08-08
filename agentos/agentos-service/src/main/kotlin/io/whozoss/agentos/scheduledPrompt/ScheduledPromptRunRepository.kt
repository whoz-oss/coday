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
     *   already exists (composite UNIQUE constraint on those two fields).
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
     * Count attempted runs for a given ScheduledPrompt, for use against [Planning.maxOccurrenceCount].
     *
     * Counts every status except SKIPPED: DONE, FAILED, CLAIMED, and RUNNING.
     * SKIPPED runs are overlap-guards that never executed — they do not consume an occurrence.
     * FAILED runs **do** consume an occurrence: the slot was attempted (a Case was created or
     * materialize was called), regardless of outcome. This is intentional — the quota tracks
     * "how many times the prompt fired", not "how many times it succeeded".
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
     * Find RUNNING Runs whose UserRuns are ALL in a terminal status (DONE, TIMEOUT, or FAILED).
     *
     * A Run is "settled" when none of its UserRuns are in PENDING or RUNNING status.
     * TIMEOUT is terminal: monitoring was released, the Case continues independently,
     * and the UserRun will not transition further.
     * Runs with zero UserRuns (platform-scope or no target users) are directly transitioned
     * to DONE in [ScheduledPromptExecutor.materialize] and never reach RUNNING status.
     *
     * These Runs are presumed orphaned — the instance that processed the last UserRun
     * crashed after [ScheduledPromptUserRunRepository.markTerminal] but before
     * [ScheduledPromptExecutor]'s `checkCompletion()` could transition the parent Run.
     */
    fun findSettledRunning(): List<ScheduledPromptRun>
}
