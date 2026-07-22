package io.whozoss.agentos.credential

import io.whozoss.agentos.sdk.credential.Credential
import java.util.UUID

/**
 * Persistence contract for [Credential].
 *
 * Credentials are not parent-scoped entities in the [EntityRepository] sense — they
 * are keyed on the `(userId, authSettingId)` pair. This interface exposes exactly the
 * operations needed by [CredentialService]; there is no generic CRUD surface.
 */
interface CredentialRepository {
    /** Persist a credential. Replaces any existing credential for the same `(userId, authSettingId)`. */
    fun save(credential: Credential): Credential

    /** Find the credential for a specific user–authSetting pair, or null if none exists. */
    fun findByUserAndAuthSetting(userId: UUID, authSettingId: UUID): Credential?

    /**
     * Soft-delete the credential for a specific user–authSetting pair.
     *
     * @return true if a credential was found and deleted, false if none existed.
     */
    fun deleteByUserAndAuthSetting(userId: UUID, authSettingId: UUID): Boolean

    /**
     * Soft-delete all credentials associated with a given [AuthSetting].
     * Called during cascade cleanup when an [AuthSetting] is deleted.
     *
     * @return the number of credentials deleted.
     */
    fun deleteByAuthSettingId(authSettingId: UUID): Int

    /** Find all non-removed credentials owned by a given user. */
    fun findByUserId(userId: UUID): List<Credential>
}
