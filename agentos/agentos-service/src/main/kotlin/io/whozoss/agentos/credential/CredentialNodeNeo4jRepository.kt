package io.whozoss.agentos.credential

import org.springframework.data.neo4j.repository.Neo4jRepository
import org.springframework.data.neo4j.repository.query.Query

/**
 * Spring Data Neo4j repository for [CredentialNode].
 *
 * Credentials are hard-deleted — there is no `removed` field. All queries return
 * every matching node without a soft-delete filter.
 */
interface CredentialNodeNeo4jRepository : Neo4jRepository<CredentialNode, String> {
    /**
     * Find the credential for a specific (userId, authSettingId) pair.
     * Returns null if none exists.
     */
    @Query(
        $$"""
            MATCH (c:Credential)
            WHERE c.userId = $userId AND c.authSettingId = $authSettingId
            RETURN c LIMIT 1
            """,
    )
    fun findByUserIdAndAuthSettingId(userId: String, authSettingId: String): CredentialNode?

    /**
     * Find all credentials owned by a given user, ordered by authSettingId
     * for deterministic results.
     */
    @Query(
        $$"""
            MATCH (c:Credential)
            WHERE c.userId = $userId
            RETURN c ORDER BY c.authSettingId ASC
            """,
    )
    fun findByUserId(userId: String): List<CredentialNode>

    /**
     * Find all credentials associated with a given authSetting.
     * Used for cascade cleanup when an AuthSetting is deleted.
     */
    @Query(
        $$"""
            MATCH (c:Credential)
            WHERE c.authSettingId = $authSettingId
            RETURN c
            """,
    )
    fun findByAuthSettingId(authSettingId: String): List<CredentialNode>

    /**
     * Hard-delete the credential for a specific (userId, authSettingId) pair.
     */
    @Query(
        $$"""
            MATCH (c:Credential)
            WHERE c.userId = $userId AND c.authSettingId = $authSettingId
            DETACH DELETE c
            """,
    )
    fun deleteByUserIdAndAuthSettingId(userId: String, authSettingId: String)

    /**
     * Hard-delete all credentials associated with a given authSetting.
     * Used for cascade cleanup when an AuthSetting is deleted.
     */
    @Query(
        $$"""
            MATCH (c:Credential)
            WHERE c.authSettingId = $authSettingId
            DETACH DELETE c
            """,
    )
    fun deleteByAuthSettingId(authSettingId: String)
}
