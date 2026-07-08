package io.whozoss.agentos.sdk.auth

import io.whozoss.agentos.sdk.authSetting.AuthSetting
import io.whozoss.agentos.sdk.credential.Credential
import java.util.UUID

/**
 * High-level authentication service available to plugins via [io.whozoss.agentos.sdk.tool.ToolContext].
 *
 * Orchestrates AuthSetting resolution (with 4-tier shadowing) and Credential
 * management (store, resolve, revoke). Plugins use this to authenticate
 * against external services without managing credentials directly.
 *
 * Implementations are request-scoped: created per tool invocation with the
 * resolved (namespaceId, userId) from the enclosing [io.whozoss.agentos.sdk.tool.ToolContext],
 * so callers never need to pass identity parameters explicitly.
 */
interface AuthService {
    /**
     * Resolve the effective AuthSetting for the given name in the current
     * namespace/user context, applying 4-tier shadowing.
     *
     * @throws io.whozoss.agentos.exception.ConfigNotFoundException when no
     *   AuthSetting with the given name exists in any layer.
     */
    fun resolveAuthSetting(name: String): AuthSetting

    /**
     * Resolve the user's credential for the given AuthSetting.
     * Returns null if the user has not authenticated yet.
     *
     * For OAuth credentials, this method should transparently refresh
     * expired tokens when possible (future enhancement).
     */
    fun resolveCredential(authSettingId: UUID): Credential?

    /**
     * Store or update a credential after successful authentication.
     * Upsert semantics: replaces existing credential for the same
     * (userId, authSettingId) pair.
     */
    fun storeCredential(credential: Credential): Credential

    /**
     * Revoke (delete) the user's credential for the given AuthSetting.
     * Returns true if a credential was found and deleted.
     */
    fun revokeCredential(authSettingId: UUID): Boolean
}
