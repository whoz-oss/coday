package io.whozoss.agentos.persistence.neo4j

import org.springframework.data.neo4j.repository.Neo4jRepository
import org.springframework.data.neo4j.repository.query.Query

/**
 * Spring Data Neo4j repository for [CaseNode].
 */
interface CaseNodeNeo4jRepository : Neo4jRepository<CaseNode, String> {
    /**
     * Find all non-removed cases belonging to a namespace, ordered by creation time.
     *
     * Returning `c, r, ns` (node, relationship, node) gives SDN everything it needs
     * to map the @Relationship field on [CaseNode] from the query result.
     */
    @Query(
        $$"""MATCH (c:Case)-[r:BELONGS_TO]->(ns:Namespace)
            WHERE ns.id = $namespaceId AND (c.removed IS NULL OR c.removed = false)
            RETURN c, r, ns ORDER BY c.created ASC
            """,
    )
    fun findActiveByNamespaceId(namespaceId: String): List<CaseNode>
}
