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
     * Filters by the denormalised [CaseNode.namespaceId] property rather than
     * traversing the BELONGS_TO relationship, keeping the query simple and
     * compatible with SDN's custom @Query result mapping (which does not
     * auto-inject @Relationship fields). The graph edge is still written on save
     * via [CaseNode.namespace] — it exists in the graph for traversal queries.
     */
    @Query(
        $$"""MATCH (c:Case)
            WHERE c.namespaceId = $namespaceId AND (c.removed IS NULL OR c.removed = false)
            RETURN c ORDER BY c.created ASC
            """,
    )
    fun findActiveByNamespaceId(namespaceId: String): List<CaseNode>
}
