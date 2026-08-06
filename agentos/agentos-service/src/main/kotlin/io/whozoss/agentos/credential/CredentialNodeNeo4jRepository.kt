package io.whozoss.agentos.credential

import org.springframework.data.neo4j.repository.Neo4jRepository
import org.springframework.data.neo4j.repository.query.Query
import java.time.Instant

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
     * Upsert a credential node keyed on the `(userId, authSettingId)` pair.
     *
     * MERGE matches on the business key — not the `@Id` — so a second save for
     * the same pair updates the existing node in place rather than creating a
     * duplicate. All scalar properties are SET unconditionally on both create
     * and match (`SET c = { ... }`).
     *
     * [Instant] parameters are passed as-is; the Neo4j driver serialises them
     * as native datetime values.
     */
    @Query(
        $$"""
            MERGE (c:Credential {userId: $userId, authSettingId: $authSettingId})
            SET c = {id: $id, userId: $userId, authSettingId: $authSettingId,
                     credentialType: $credentialType, dataJson: $dataJson,
                     created: $created, createdBy: $createdBy,
                     modified: $modified, modifiedBy: $modifiedBy}
            RETURN c
            """,
    )
    fun upsert(
        id: String,
        userId: String,
        authSettingId: String,
        credentialType: String,
        dataJson: String?,
        created: Instant,
        createdBy: String?,
        modified: Instant,
        modifiedBy: String?,
    ): CredentialNode

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
