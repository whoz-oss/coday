package io.whozoss.agentos.persistence.neo4j

import org.springframework.data.neo4j.repository.Neo4jRepository
import org.springframework.data.neo4j.repository.query.Query

/**
 * Spring Data Neo4j repository for [IntegrationConfigNode].
 */
interface IntegrationConfigNodeNeo4jRepository : Neo4jRepository<IntegrationConfigNode, String> {
    /**
     * Find all non-removed integration configs belonging to a namespace, ordered by name.
     */
    @Query(
        "MATCH (c:IntegrationConfig) " +
            "WHERE c.namespaceId = \$namespaceId AND (c.removed IS NULL OR c.removed = false) " +
            "RETURN c ORDER BY c.name ASC",
    )
    fun findActiveByNamespaceId(namespaceId: String): List<IntegrationConfigNode>
}
