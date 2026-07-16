package io.whozoss.agentos.authSetting

import org.springframework.data.neo4j.repository.Neo4jRepository
import org.springframework.data.neo4j.repository.query.Query

/**
 * Spring Data Neo4j repository for [AuthSettingNode].
 */
interface AuthSettingNodeNeo4jRepository : Neo4jRepository<AuthSettingNode, String> {
    /**
     * Find all non-removed namespace-shared auth settings (userId IS NULL), ordered by name.
     *
     * Filters `userId IS NULL` so that user-scoped overrides are hidden from
     * namespace-scope listings.
     */
    @Query(
        $$"""
            MATCH (c:AuthSetting)
            WHERE c.namespaceId = $namespaceId AND c.userId IS NULL AND (c.removed IS NULL OR c.removed = false)
            RETURN c ORDER BY c.name ASC
            """,
    )
    fun findActiveByNamespaceId(namespaceId: String): List<AuthSettingNode>

    /**
     * Find all non-removed auth settings scoped to a user, ordered by name then id.
     * The `id` tie-breaker keeps pagination deterministic when two settings share a name
     * (legal across user-global and user x namespace modes).
     */
    @Query(
        $$"""
            MATCH (c:AuthSetting)
            WHERE c.userId = $userId AND (c.removed IS NULL OR c.removed = false)
            RETURN c ORDER BY c.name ASC, c.id ASC
            """,
    )
    fun findActiveByUserId(userId: String): List<AuthSettingNode>

    /**
     * Find all non-removed platform-level settings (namespaceId IS NULL AND userId IS NULL).
     */
    @Query(
        $$"""
            MATCH (c:AuthSetting)
            WHERE c.namespaceId IS NULL AND c.userId IS NULL AND (c.removed IS NULL OR c.removed = false)
            RETURN c ORDER BY c.name ASC
            """,
    )
    fun findActivePlatformLevel(): List<AuthSettingNode>

    /**
     * Find a single non-removed setting matched by its [AuthSettingNode.tripleKey] discriminator.
     *
     * The unique constraint on `tripleKey` provisions an index that backs this lookup with
     * an exact seek instead of a label scan.
     */
    @Query(
        $$"""
            MATCH (c:AuthSetting {tripleKey: $tripleKey})
            WHERE (c.removed IS NULL OR c.removed = false)
            RETURN c LIMIT 1
            """,
    )
    fun findActiveByTripleKey(tripleKey: String): AuthSettingNode?

    /**
     * Fetch all non-removed settings visible for a given (namespaceId, userId) execution
     * context in a single query -- platform layer + namespace-shared layer + user layers.
     *
     * The cross-product of the two OR-clauses covers all four layers:
     * platform (null,null), namespace-shared (ns,null), user-global (null,user),
     * user x namespace (ns,user).
     */
    @Query(
        $$"""
            MATCH (c:AuthSetting)
            WHERE (c.namespaceId IS NULL OR c.namespaceId = $namespaceId)
            AND (c.userId IS NULL OR c.userId = $userId)
            AND (c.removed IS NULL OR c.removed = false)
            RETURN c
            """,
    )
    fun findAllForNamespaceAndUser(
        namespaceId: String,
        userId: String,
    ): List<AuthSettingNode>
}
