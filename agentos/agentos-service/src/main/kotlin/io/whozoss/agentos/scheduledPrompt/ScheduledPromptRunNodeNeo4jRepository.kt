package io.whozoss.agentos.scheduledPrompt

import org.springframework.data.neo4j.repository.Neo4jRepository
import org.springframework.data.neo4j.repository.query.Query
import java.time.Instant

/**
 * Spring Data Neo4j repository for [ScheduledPromptRunNode].
 */
interface ScheduledPromptRunNodeNeo4jRepository : Neo4jRepository<ScheduledPromptRunNode, String> {

    /**
     * Returns true if there is at least one run for [scheduledPromptId] in an active status
     * (CLAIMED or RUNNING).
     */
    @Query(
        $$"""
        MATCH (r:ScheduledPromptRun)
        WHERE r.scheduledPromptId = $scheduledPromptId
        AND r.status IN ['CLAIMED', 'RUNNING']
        AND NOT COALESCE(r.removed, false)
        RETURN count(r) > 0
        """,
    )
    fun existsActiveByScheduledPromptId(scheduledPromptId: String): Boolean

    /**
     * Update status (and optionally finishedAt + error) of a Run by id.
     * Returns the count of updated nodes (0 if not found).
     */
    @Query(
        $$"""
        MATCH (r:ScheduledPromptRun {id: $id})
        SET r.status = $status,
            r.finishedAt = $finishedAt,
            r.error = $error,
            r.modified = $now
        RETURN count(r)
        """,
    )
    fun updateStatus(id: String, status: String, finishedAt: Instant?, error: String?, now: Instant): Int

    /**
     * Count non-SKIPPED runs for a given ScheduledPrompt.
     */
    @Query(
        $$"""
        MATCH (r:ScheduledPromptRun)
        WHERE r.scheduledPromptId = $scheduledPromptId
          AND r.status <> 'SKIPPED'
          AND NOT COALESCE(r.removed, false)
        RETURN count(r)
        """,
    )
    fun countCompletedRuns(scheduledPromptId: String): Int

    /**
     * Find all runs in [status] created before [before], ordered by creation time.
     */
    @Query($$"MATCH (r:ScheduledPromptRun) WHERE r.status = $status AND r.created < $before RETURN r ORDER BY r.created")
    fun findByStatusAndCreatedBefore(status: String, before: Instant): List<ScheduledPromptRunNode>

}
