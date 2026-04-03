package io.whozoss.agentos.persistence.neo4j

import org.springframework.data.neo4j.repository.Neo4jRepository
import org.springframework.data.neo4j.repository.query.Query

/**
 * Spring Data Neo4j repository for [CaseEventNode].
 */
interface CaseEventNodeNeo4jRepository : Neo4jRepository<CaseEventNode, String> {
    /**
     * Find all non-removed events for a case, ordered by timestamp then id
     * (stable sort matching [FilesystemCaseEventRepository]).
     */
    @Query(
        "MATCH (e:CaseEvent) " +
            "WHERE e.caseId = \$caseId AND e.removed = false " +
            "RETURN e ORDER BY e.timestamp ASC, e.id ASC",
    )
    fun findActiveByCaseId(caseId: String): List<CaseEventNode>
}
