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

    /**
     * Creates the `BELONGS_TO` relationship from a CaseEvent node to its Case node.
     *
     * Called after saving a new event. Using an explicit query avoids SDN writing
     * stub [CaseNode] properties (empty status/title) onto the existing Case node
     * when the relationship is expressed via the @Relationship field.
     */
    @Query(
        $$"""MATCH (e:CaseEvent {id: $eventId})
            MATCH (c:Case {id: $caseId})
            MERGE (e)-[:BELONGS_TO]->(c)
            """,
    )
    fun linkEventToCase(
        eventId: String,
        caseId: String,
    )
}
