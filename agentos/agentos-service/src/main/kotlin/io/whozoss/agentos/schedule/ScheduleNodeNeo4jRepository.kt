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
        """
            MATCH (s:Schedule)
            WHERE s.namespaceId = ${'$'}namespaceId AND (s.removed IS NULL OR s.removed = false)
            RETURN s
            ORDER BY
              CASE WHEN s.nextTriggerAt IS NULL THEN 1 ELSE 0 END ASC,
              s.nextTriggerAt ASC,
              s.created ASC
            """,
    )
    fun findActiveByNamespaceId(namespaceId: String): List<ScheduleNode>

    /**
     * Find all enabled, non-removed schedules whose nextTriggerAt is at or
     * before [now], ordered by nextTriggerAt ascending.
     *
     * [now] is passed as an ISO-8601 string (e.g. [Instant.toString]) and
     * converted to a Neo4j DateTime via datetime() so the comparison is
     * temporal rather than lexicographic. Without this conversion Neo4j would
     * compare a DateTime property against a String parameter, producing
     * undefined results (always false in practice).
     */
    @Query(
        """
            MATCH (s:Schedule)
            WHERE s.enabled = true
              AND (s.removed IS NULL OR s.removed = false)
              AND s.nextTriggerAt IS NOT NULL
              AND s.nextTriggerAt <= datetime(${'$'}now)
            RETURN s
            ORDER BY s.nextTriggerAt ASC
            """,
    )
    fun findDueByNow(now: String): List<ScheduleNode>
}
