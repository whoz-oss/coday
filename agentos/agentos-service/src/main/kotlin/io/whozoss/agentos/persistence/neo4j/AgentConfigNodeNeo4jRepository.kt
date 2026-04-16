package io.whozoss.agentos.persistence.neo4j

import org.springframework.data.neo4j.repository.Neo4jRepository
import org.springframework.data.neo4j.repository.query.Query

/**
 * Spring Data Neo4j repository for [AgentConfigNode].
 */
interface AgentConfigNodeNeo4jRepository : Neo4jRepository<AgentConfigNode, String> {
    /**
     * Find all non-removed agent configs belonging to a namespace, ordered by name.
     */
    @Query(
        "MATCH (a:AgentConfig) " +
            "WHERE a.namespaceId = \$namespaceId AND (a.removed IS NULL OR a.removed = false) " +
            "RETURN a ORDER BY a.name ASC",
    )
    fun findActiveByNamespaceId(namespaceId: String): List<AgentConfigNode>
}
