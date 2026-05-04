package io.whozoss.agentos.caseEvent

import org.springframework.data.neo4j.repository.Neo4jRepository
import org.springframework.data.neo4j.repository.query.Query

/**
 * Spring Data Neo4j repository for [CaseEventNode].
 */
interface CaseEventNodeNeo4jRepository : Neo4jRepository<CaseEventNode, String> {
    /**
     * Find all non-removed events for a case, ordered by timestamp then id.
     *
     * Returns `e, r, c` so SDN maps the [CaseEventNode.case] @Relationship field.
     */
    @Query(
        $$"""MATCH (e:CaseEvent)
            WHERE e.caseId = $caseId AND (e.removed IS NULL OR e.removed = false)
            OPTIONAL MATCH (e)-[r:BELONGS_TO]->(c:Case)
            RETURN e, r, c ORDER BY e.timestamp ASC, e.id ASC
            """,
    )
    fun findActiveByCaseId(caseId: String): List<CaseEventNode>
}
