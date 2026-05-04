package io.whozoss.agentos.integrationConfig

import org.springframework.data.neo4j.repository.Neo4jRepository
import org.springframework.data.neo4j.repository.query.Query

/**
 * Spring Data Neo4j repository for [IntegrationConfigNode].
 */
interface IntegrationConfigNodeNeo4jRepository : Neo4jRepository<IntegrationConfigNode, String> {
    /**
     * Find all non-removed integration configs belonging to a namespace, ordered by name.
     *
     * Traverses the BELONGS_TO edge and filters by the Namespace id. The edge is
     * always present because [linkConfigToNamespace] is called after every save.
     * Returning `c, r, ns` gives SDN everything it needs to map the
     * [IntegrationConfigNode.namespace] @Relationship field.
     */
    @Query(
        $$"""
            MATCH (c:IntegrationConfig)-[r:BELONGS_TO]->(ns:Namespace)
            WHERE ns.id = $namespaceId AND (c.removed IS NULL OR c.removed = false)
            RETURN c, r, ns ORDER BY c.name ASC
            """,
    )
    fun findActiveByNamespaceId(namespaceId: String): List<IntegrationConfigNode>
}
