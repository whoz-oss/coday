package io.whozoss.agentos.persistence.neo4j

import org.springframework.data.neo4j.repository.Neo4jRepository
import org.springframework.data.neo4j.repository.query.Query

/**
 * Spring Data Neo4j repository for [IntegrationConfigNode].
 */
interface IntegrationConfigNodeNeo4jRepository : Neo4jRepository<IntegrationConfigNode, String> {
    /**
     * Find all non-removed integration configs belonging to a namespace, ordered by name.
     *
     * Filters by the denormalised [IntegrationConfigNode.namespaceId] property rather
     * than traversing the BELONGS_TO relationship, keeping the query simple and
     * compatible with SDN's custom @Query result mapping (which does not
     * auto-inject @Relationship fields). The graph edge is still written on save
     * via [IntegrationConfigNode.namespace] — it exists in the graph for traversal.
     */
    @Query(
        $$"""MATCH (c:IntegrationConfig)
            WHERE c.namespaceId = $namespaceId AND (c.removed IS NULL OR c.removed = false)
            RETURN c ORDER BY c.name ASC
            """,
    )
    fun findActiveByNamespaceId(namespaceId: String): List<IntegrationConfigNode>
}
