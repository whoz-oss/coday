package io.whozoss.agentos.schedule

import org.springframework.data.neo4j.repository.Neo4jRepository
import org.springframework.data.neo4j.repository.query.Query

/**
 * Spring Data Neo4j repository for [ScheduleNode].
 */
interface ScheduleNodeNeo4jRepository : Neo4jRepository<ScheduleNode, String> {
    /**
     * Find all non-removed schedules belonging to a namespace, ordered by
     * nextTriggerAt (nulls last) then by creation time.
     */
    @Query(
        $$"""
            MATCH (s:Schedule)
            WHERE s.namespaceId = $namespaceId AND (s.removed IS NULL OR s.removed = false)
            RETURN s
            ORDER BY
              CASE WHEN s.nextTriggerAt IS NULL THEN 1 ELSE 0 END ASC,
              s.nextTriggerAt ASC,
              s.created ASC
            """,
    )
    fun findActiveByNamespaceId(namespaceId: String): List<ScheduleNode>
}
