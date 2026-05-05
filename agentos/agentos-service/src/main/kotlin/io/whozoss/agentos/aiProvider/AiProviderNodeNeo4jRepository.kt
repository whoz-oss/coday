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
     * Find all non-removed AI provider configs scoped to a user, ordered by name then id.
     * The `id` tie-breaker keeps pagination deterministic when two providers share a name
     * (legal across user-global and user × namespace modes).
     */
    @Query(
        $$"""
            MATCH (c:AiProvider)
            WHERE c.userId = $userId AND (c.removed IS NULL OR c.removed = false)
            RETURN c ORDER BY c.name ASC, c.id ASC
            """,
    )
    fun findActiveByUserId(userId: String): List<AiProviderNode>
}
