package io.whozoss.agentos.scheduledPrompt

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
}
