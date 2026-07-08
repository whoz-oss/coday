package io.whozoss.agentos.sdk.credential

import com.fasterxml.jackson.annotation.JsonIgnoreProperties
import io.whozoss.agentos.sdk.entity.Entity
import io.whozoss.agentos.sdk.entity.EntityMetadata
import java.util.UUID

/**
 * Stored result of an individual user's authentication against a specific [AuthSetting].
 *
 * Strictly personal and transactional — one credential per user per auth setting, scoped
 * to the `(userId, authSettingId)` pair. There is no namespace dimension: credentials are
 * always personal and never shared across users.
 *
 * [data] holds the actual credential material (tokens, keys, passwords) and is
 * **encrypted at rest** — the persistence layer encrypts each map value individually
 * before writing and decrypts on read.
 *
 * No REST endpoint: credentials are managed exclusively via `AuthService` (the service
 * that orchestrates the full authentication flow). They are never exposed directly over HTTP.
 *
 * Cascade: deleting an `AuthSetting` must cascade-delete all associated credentials
 * (via [authSettingId]). `AuthService` is responsible for triggering this cleanup.
 */
@JsonIgnoreProperties(ignoreUnknown = true)
data class Credential(
    override val metadata: EntityMetadata = EntityMetadata(),
    /** The user who owns this credential. Always non-null — credentials are always personal. */
    val userId: UUID,
    /** The [AuthSetting] this credential authenticates against, referenced by id. */
    val authSettingId: UUID,
    /** The type of stored credential, determining which keys are expected in [data]. */
    val credentialType: CredentialType,
    /**
     * The actual credential material, encrypted at rest.
     *
     * Expected keys by type:
     * - [CredentialType.OAUTH_TOKENS]: `accessToken`, `refreshToken`, `expiresAt`, `tokenType`, `scope`
     * - [CredentialType.API_KEY]: `key`
     * - [CredentialType.BEARER_TOKEN]: `token`
     * - [CredentialType.BASIC_AUTH]: `username`, `password`
     */
    val data: Map<String, String> = emptyMap(),
) : Entity
