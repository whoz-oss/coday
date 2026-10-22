package io.whozoss.agentos.scheduledPrompt

import org.springframework.data.neo4j.repository.Neo4jRepository
import org.springframework.data.neo4j.repository.query.Query
import org.springframework.transaction.annotation.Transactional
import java.time.Instant

/**
 * Spring Data Neo4j repository for [ScheduledPromptRunNode].
 */
interface ScheduledPromptRunNodeNeo4jRepository : Neo4jRepository<ScheduledPromptRunNode, String> {

    /**
     * Returns true if there is at least one run for [scheduledPromptId] in an active status
     * (CLAIMED, TARGETING, or RUNNING).
     *
     * TARGETING is included because the converged path holds the run in TARGETING while the
     * loop case executes — without it, a second tick would claim a new run concurrently.
     */
    @Query(
        $$"""
        MATCH (r:ScheduledPromptRun)
        WHERE r.scheduledPromptId = $scheduledPromptId
        AND r.status IN ['CLAIMED', 'TARGETING', 'RUNNING']
        AND NOT COALESCE(r.removed, false)
        RETURN count(r) > 0
        """,
    )
    fun existsActiveByScheduledPromptId(scheduledPromptId: String): Boolean

    /**
     * Update status (and optionally finishedAt + error) of a Run by id.
     * Only updates if the Run is not already in a terminal status (DONE or FAILED) —
     * prevents concurrent calls to recoverOrphanedRunningRuns from overwriting a finishedAt
     * already set by a previous invocation.
     * Returns the count of updated nodes (0 if not found or already terminal).
     */
    @Query(
        $$"""
        MATCH (r:ScheduledPromptRun {id: $id})
        WHERE NOT r.status IN ['DONE', 'FAILED']
        SET r.status = $status,
            r.finishedAt = $finishedAt,
            r.error = $error,
            r.modified = $now
        RETURN count(r)
        """,
    )
    fun updateStatus(id: String, status: String, finishedAt: Instant?, error: String?, now: Instant): Int

    /**
     * Count all non-removed runs for a given ScheduledPrompt within the current planning window,
     * including SKIPPED. Only runs with [scheduledFor] >= [startInstant] are counted so that
     * runs from a previous planning window (before the user moved [startDate] forward) do not
     * consume quota in the new window.
     *
     * SKIPPED runs represent slots that fired but overlapped with an active run —
     * the slot still occurred and counts toward the occurrence quota.
     */
    @Query(
        $$"""
        MATCH (r:ScheduledPromptRun)
        WHERE r.scheduledPromptId = $scheduledPromptId
        AND r.scheduledFor >= $startInstant
        RETURN count(r)
        """,
    )
    fun countCompletedRuns(scheduledPromptId: String, startInstant: Instant): Int

    /**
     * Find all runs in [status] created before [before], ordered by creation time.
     */
    @Query($$"MATCH (r:ScheduledPromptRun) WHERE r.status = $status AND r.created < $before RETURN r ORDER BY r.created")
    fun findByStatusAndCreatedBefore(status: String, before: Instant): List<ScheduledPromptRunNode>

    /**
     * Find a run by its associated loop case id.
     */
    @Transactional(readOnly = true)
    @Query($$"""MATCH (r:ScheduledPromptRun {loopCaseId: $loopCaseId}) RETURN r LIMIT 1""")
    fun findByLoopCaseId(loopCaseId: String): ScheduledPromptRunNode?

    /**
     * Atomically update status and loopCaseId on a run.
     * Used by the executor to record the loop case before injecting the trigger message.
     */
    @Query(
        $$"""
        MATCH (r:ScheduledPromptRun {id: $id})
        SET r.status = $status, r.loopCaseId = $loopCaseId, r.modified = $now
        RETURN count(r)
        """,
    )
    fun updateStatusAndLoopCase(id: String, status: String, loopCaseId: String, now: Instant): Int

    /**
     * Find RUNNING Runs whose UserRuns are all terminal (none in PENDING or RUNNING).
     *
     * Terminal UserRun statuses: DONE, TIMEOUT, FAILED. TIMEOUT is terminal — monitoring
     * was released, the Case continues independently, and the UserRun will not transition
     * further. A Run settled with only TIMEOUT/DONE UserRuns closes as DONE; one with at
     * least one FAILED closes as FAILED.
     *
     * Runs with zero UserRuns never reach RUNNING status (they are transitioned directly
     * to DONE in [ScheduledPromptExecutor.materialize]).
     *
     * **TARGETING is excluded by design and must never be added to this query.**
     * The converged path writes NO UserRun rows, so the NOT EXISTS sub-query is always
     * true for TARGETING runs — adding them here would close every live converged run
     * as DONE within ~30s of starting. Orphaned TARGETING runs are swept by
     * [SchedulerScanner.recoverOrphanedTargetingRuns] using its own threshold.
     */
    @Query(
        """
        MATCH (r:ScheduledPromptRun)
        WHERE r.status = 'RUNNING'
          AND NOT COALESCE(r.removed, false)
          AND NOT EXISTS {
              MATCH (ur:ScheduledPromptUserRun)
              WHERE ur.runId = r.id AND ur.status IN ['PENDING', 'RUNNING']
          }
        RETURN r
        ORDER BY r.created
        """,
    )
    fun findSettledRunning(): List<ScheduledPromptRunNode>

}
