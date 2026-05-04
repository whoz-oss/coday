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
     * Find a single non-removed config matched by its [IntegrationConfigNode.tripleKey] discriminator.
     *
     * The lookup is a single-property exact match on a non-null String, which the Neo4j planner
     * resolves through the index provisioned by the `integration_config_triple_key_unique`
     * constraint. This sidesteps the two limitations of the previous composite-index lookup:
     * indexes were not used for `IS NULL` predicates, and the composite uniqueness constraint
     * silently exempted rows where any component was NULL.
     *
     * Callers must compute the key with [IntegrationConfigNode.computeTripleKey] to keep the
     * encoding identical to what was written via [IntegrationConfigNode.fromDomain].
     */
    @Query(
        $$"""
            MATCH (c:IntegrationConfig {tripleKey: $tripleKey})
            WHERE c.removed IS NULL OR c.removed = false
            RETURN c
            LIMIT 1
            """,
    )
    fun findActiveByTripleKey(tripleKey: String): IntegrationConfigNode?
}
