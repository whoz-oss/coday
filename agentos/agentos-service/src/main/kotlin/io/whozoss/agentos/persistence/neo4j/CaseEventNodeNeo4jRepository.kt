package io.whozoss.agentos.persistence.neo4j

import org.springframework.data.neo4j.repository.Neo4jRepository
import org.springframework.data.neo4j.repository.query.Query

/**
 * Spring Data Neo4j repository for [CaseEventNode].
 */
interface CaseEventNodeNeo4jRepository : Neo4jRepository<CaseEventNode, String> {
    /**
     * Find all non-removed events for a case, ordered by timestamp then id.
     *
     * Filters by the [CaseEventNode.caseId] scalar property. Returns `e, r, c`
     * so SDN can map the [CaseEventNode.case] @Relationship field.
     */
    @Query(
        $$"""MATCH (e:CaseEvent)-[r:BELONGS_TO]->(c:Case)
            WHERE e.caseId = $caseId AND (e.removed IS NULL OR e.removed = false)
            RETURN e, r, c ORDER BY e.timestamp ASC, e.id ASC
            """,
    )
    fun findActiveByCaseId(caseId: String): List<CaseEventNode>
}
