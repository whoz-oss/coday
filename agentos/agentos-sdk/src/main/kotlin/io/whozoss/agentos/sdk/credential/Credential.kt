package io.whozoss.agentos.sdk.credential

import com.fasterxml.jackson.annotation.JsonIgnoreProperties
import io.whozoss.agentos.sdk.entity.Entity
import io.whozoss.agentos.sdk.entity.EntityMetadata
import java.util.UUID

/**
 * Stored result of an individual user's authentication against a specific AuthSetting.
 *
 * Strictly personal — one credential per user per auth setting, scoped to the
 * `(userId, authSettingId)` pair.
 *
 * [data] holds the actual credential material (tokens, keys, passwords) and is
 * **encrypted at rest**.
 *
 * Expected keys by [credentialType]:
 * - [CredentialType.OAUTH_TOKENS]: `accessToken`, `refreshToken`, `expiresAt`, `tokenType`, `scope`
 * - [CredentialType.API_KEY]: `key`
 * - [CredentialType.BEARER_TOKEN]: `token`
 * - [CredentialType.BASIC_AUTH]: `username`, `password`
 */
@JsonIgnoreProperties(ignoreUnknown = true)
data class Credential(
    override val metadata: EntityMetadata = EntityMetadata(),
    val userId: UUID,
    val authSettingId: UUID,
    val credentialType: CredentialType,
    val data: Map<String, String> = emptyMap(),
) : Entity
