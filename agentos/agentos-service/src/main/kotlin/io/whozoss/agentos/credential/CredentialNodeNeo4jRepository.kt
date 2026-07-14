package io.whozoss.agentos.credential

import org.springframework.data.neo4j.repository.Neo4jRepository
import org.springframework.data.neo4j.repository.query.Query

/**
 * Spring Data Neo4j repository for [CredentialNode].
 */
interface CredentialNodeNeo4jRepository : Neo4jRepository<CredentialNode, String> {
    /**
     * Find the non-removed credential for a specific (userId, authSettingId) pair.
     * Returns null if none exists.
     */
    @Query(
        $$"""
            MATCH (c:Credential)
            WHERE c.userId = $userId AND c.authSettingId = $authSettingId
            AND (c.removed IS NULL OR c.removed = false)
            RETURN c LIMIT 1
            """,
    )
    fun findActiveByUserIdAndAuthSettingId(userId: String, authSettingId: String): CredentialNode?

    /**
     * Find all non-removed credentials owned by a given user, ordered by authSettingId
     * for deterministic results.
     */
    @Query(
        $$"""
            MATCH (c:Credential)
            WHERE c.userId = $userId AND (c.removed IS NULL OR c.removed = false)
            RETURN c ORDER BY c.authSettingId ASC
            """,
    )
    fun findActiveByUserId(userId: String): List<CredentialNode>

    /**
     * Find all non-removed credentials associated with a given authSetting.
     * Used for cascade cleanup when an AuthSetting is deleted.
     */
    @Query(
        $$"""
            MATCH (c:Credential)
            WHERE c.authSettingId = $authSettingId AND (c.removed IS NULL OR c.removed = false)
            RETURN c
            """,
    )
    fun findActiveByAuthSettingId(authSettingId: String): List<CredentialNode>
}
