package io.whozoss.agentos.aiProvider

import org.springframework.data.neo4j.repository.Neo4jRepository
import org.springframework.data.neo4j.repository.query.Query

/**
 * Spring Data Neo4j repository for [AiProviderNode].
 */
interface AiProviderNodeNeo4jRepository : Neo4jRepository<AiProviderNode, String> {
    /**
     * Find all non-removed namespace-shared AI provider configs (userId IS NULL), ordered by name.
     *
     * Story 6.4 AC14: filters `userId IS NULL` so that user-scoped overrides are hidden from
     * namespace-scope listings (FR22, AR8).
     */
    @Query(
        $$"""
            MATCH (c:AiProvider)
            WHERE c.namespaceId = $namespaceId AND c.userId IS NULL AND (c.removed IS NULL OR c.removed = false)
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

    /**
     * Find a single non-removed config matched by its [AiProviderNode.tripleKey] discriminator.
     *
     * The unique constraint on `tripleKey` provisions an index that backs this lookup with
     * an exact seek (vs the legacy [findActiveByTriple] which falls back to a label scan
     * on the NULL-arms because Neo4j 5.x indexes do not seek on `IS NULL`).
     */
    @Query(
        $$"""
            MATCH (c:AiProvider {tripleKey: $tripleKey})
            WHERE (c.removed IS NULL OR c.removed = false)
            RETURN c LIMIT 1
            """,
    )
    fun findActiveByTripleKey(tripleKey: String): AiProviderNode?
}
