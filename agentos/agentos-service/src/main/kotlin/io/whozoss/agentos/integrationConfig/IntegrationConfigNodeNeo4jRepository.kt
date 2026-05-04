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
     * always present for namespace-scoped configs because
     * [Neo4jIntegrationConfigRepository.save] calls
     * [io.whozoss.agentos.persistence.Neo4jChildLinkService.link] right after the entity
     * write. Returning `c, r, ns` gives SDN everything it needs to map the
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

    /**
     * Find all non-removed integration configs scoped to a user, ordered by name.
     *
     * Filters on the scalar [IntegrationConfigNode.userId] property (no edge traversal)
     * because user-only configs do not yet materialise a BELONGS_TO edge — that wiring
     * is added in story 6.2.
     */
    @Query(
        $$"""
            MATCH (c:IntegrationConfig)
            WHERE c.userId = $userId AND (c.removed IS NULL OR c.removed = false)
            RETURN c ORDER BY c.name ASC
            """,
    )
    fun findActiveByUserId(userId: String): List<IntegrationConfigNode>

    /**
     * Find a single non-removed config matching the (namespaceId, userId, name) triple.
     * Both ids are nullable; NULL parameters match rows where the corresponding property is NULL
     * (Neo4j stores NULL as the absence of a property — `c.namespaceId IS NULL` covers this).
     */
    @Query(
        $$"""
            MATCH (c:IntegrationConfig)
            WHERE c.name = $name
              AND (c.removed IS NULL OR c.removed = false)
              AND (
                ($namespaceId IS NULL AND c.namespaceId IS NULL)
                OR c.namespaceId = $namespaceId
              )
              AND (
                ($userId IS NULL AND c.userId IS NULL)
                OR c.userId = $userId
              )
            RETURN c
            LIMIT 1
            """,
    )
    fun findActiveByTriple(
        namespaceId: String?,
        userId: String?,
        name: String,
    ): IntegrationConfigNode?
}
