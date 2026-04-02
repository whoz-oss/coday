package io.whozoss.agentos.persistence.neo4j

import org.springframework.data.neo4j.repository.Neo4jRepository
import org.springframework.data.neo4j.repository.query.Query

/**
 * Spring Data Neo4j repository for [CaseNode].
 */
interface CaseNeo4jRepository : Neo4jRepository<CaseNode, String> {
    /**
     * Find all non-removed cases belonging to a namespace, ordered by creation time.
     */
    @Query(
        "MATCH (c:Case) " +
            "WHERE c.namespaceId = \$namespaceId AND c.removed = false " +
            "RETURN c ORDER BY c.created ASC",
    )
    fun findActiveByNamespaceId(namespaceId: String): List<CaseNode>
}
