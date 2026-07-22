package io.whozoss.agentos.credential

import io.whozoss.agentos.sdk.credential.Credential
import java.util.UUID

/**
 * Business-logic contract for credential lifecycle management.
 *
 * Credentials are managed exclusively through this interface — there is no REST
 * controller. `AuthService` is the primary caller.
 */
interface CredentialService {
    /**
     * Store a credential for the given user–authSetting pair.
     * Upsert semantics: replaces any existing credential for the same pair.
     */
    fun store(credential: Credential): Credential

    /** Retrieve the credential for a specific user–authSetting pair, or null if none exists. */
    fun resolve(userId: UUID, authSettingId: UUID): Credential?

    /**
     * Delete the credential for a specific user–authSetting pair.
     *
     * @return true if a credential existed and was deleted, false otherwise.
     */
    fun revoke(userId: UUID, authSettingId: UUID): Boolean

    /**
     * Delete all credentials associated with a given authSetting.
     * Called during cascade cleanup when an [AuthSetting] is deleted.
     *
     * @return the number of credentials revoked.
     */
    fun revokeByAuthSetting(authSettingId: UUID): Int

    /** Find all credentials owned by a given user. */
    fun findByUserId(userId: UUID): List<Credential>
}
