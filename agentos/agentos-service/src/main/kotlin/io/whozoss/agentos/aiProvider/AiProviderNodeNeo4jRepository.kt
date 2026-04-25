package io.whozoss.agentos.aiProvider

import org.springframework.data.neo4j.repository.Neo4jRepository
import org.springframework.data.neo4j.repository.query.Query

/**
 * Spring Data Neo4j repository for [AiProviderNode].
 */
interface AiProviderNodeNeo4jRepository : Neo4jRepository<AiProviderNode, String> {
    /**
     * Find all non-removed AI provider configs scoped to a namespace, ordered by name.
     */
    @Query(
        $$"""
            MATCH (c:AiProvider)
            WHERE c.namespaceId = $namespaceId AND (c.removed IS NULL OR c.removed = false)
            RETURN c ORDER BY c.name ASC
            """,
    )
    fun findActiveByNamespaceId(namespaceId: String): List<AiProviderNode>

    /**
     * Find all non-removed AI provider configs scoped to a user, ordered by name.
     */
    @Query(
        $$"""
            MATCH (c:AiProvider)
            WHERE c.userId = $userId AND (c.removed IS NULL OR c.removed = false)
            RETURN c ORDER BY c.name ASC
            """,
    )
    fun findActiveByUserId(userId: String): List<AiProviderNode>

    /**
     * Creates the `BELONGS_TO` relationship from an AiProvider node to its
     * parent Namespace node (Story 4.3).
     *
     * Called after every save of a namespace-scoped AiProvider. Using an
     * explicit query avoids SDN writing stub [io.whozoss.agentos.namespace.NamespaceNode]
     * properties (empty name / description) onto the existing Namespace node
     * when the relationship is expressed via the @Relationship field.
     */
    @Query(
        $$"""MATCH (p:AiProvider {id: $aiProviderId})
            MATCH (ns:Namespace {id: $namespaceId})
            MERGE (p)-[:BELONGS_TO]->(ns)
            """,
    )
    fun linkAiProviderToNamespace(
        aiProviderId: String,
        namespaceId: String,
    )
}
