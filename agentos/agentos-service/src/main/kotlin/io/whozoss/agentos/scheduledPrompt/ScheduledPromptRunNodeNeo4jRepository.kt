package io.whozoss.agentos.scheduledPrompt

import org.springframework.data.neo4j.repository.Neo4jRepository
import org.springframework.data.neo4j.repository.query.Query

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
}
