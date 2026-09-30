package io.whozoss.agentos.auth

import io.whozoss.agentos.authSetting.ApiKeyAuthSetting
import io.whozoss.agentos.authSetting.AuthSetting
import io.whozoss.agentos.authSetting.BasicAuthAuthSetting
import io.whozoss.agentos.authSetting.BearerTokenAuthSetting
import io.whozoss.agentos.authSetting.OAuthCustomAuthSetting
import io.whozoss.agentos.authSetting.OAuthDiscoverableAuthSetting
import io.whozoss.agentos.authSetting.OAuthMcpDiscoverableAuthSetting
import io.whozoss.agentos.authSetting.OAuthRegisteredAuthSetting
import io.whozoss.agentos.encryption.FieldEncryptor
import io.whozoss.agentos.encryption.NoOpFieldEncryptor
import io.whozoss.agentos.sdk.credential.Credential
import io.whozoss.agentos.sdk.credential.CredentialType
import io.whozoss.agentos.sdk.entity.EntityMetadata
import mu.KLogging
import org.springframework.stereotype.Component
import java.util.UUID

/**
 * Turns the static secret carried by an [AuthSetting] (API key, bearer token, basic-auth
 * password) into an in-memory [Credential] that a plugin can consume through its
 * `CredentialProvider`.
 *
 * Pure and stateless: nothing is persisted, nothing is read from the database. The returned
 * [Credential] carries a fresh transient [EntityMetadata] and must never be stored; the
 * per-user `Credential` rows written by [OAuthFlowService] are the only persisted ones and
 * always take precedence over what this factory synthesises.
 *
 * The `data` keys follow the SDK contract documented on [Credential]: `key` for
 * [CredentialType.API_KEY] (the setting field is called `apiKey`), `token` for
 * [CredentialType.BEARER_TOKEN], `username` / `password` for [CredentialType.BASIC_AUTH].
 *
 * OAuth setting types are owned by [OAuthFlowService] and are never synthesised here.
 */
@Component
class StaticCredentialFactory(
    fieldEncryptor: FieldEncryptor,
) {
    init {
        if (fieldEncryptor is NoOpFieldEncryptor) {
            logger.warn {
                "[StaticCredentialFactory] Field encryption is disabled: static AuthSetting secrets " +
                    "(API keys, bearer tokens, basic-auth passwords) are stored in plaintext at rest " +
                    "and will be handed to plugins as-is."
            }
        }
    }

    /**
     * Returns the static credential of [setting] for [userId], or `null` when the setting is an
     * OAuth type or its secret is blank.
     */
    fun fromAuthSetting(
        userId: UUID,
        setting: AuthSetting,
    ): Credential? =
        when (setting) {
            is ApiKeyAuthSetting ->
                staticCredential(
                    userId = userId,
                    setting = setting,
                    credentialType = CredentialType.API_KEY,
                    secret = setting.apiKey,
                    data = mapOf("key" to setting.apiKey),
                )

            is BearerTokenAuthSetting ->
                staticCredential(
                    userId = userId,
                    setting = setting,
                    credentialType = CredentialType.BEARER_TOKEN,
                    secret = setting.token,
                    data = mapOf("token" to setting.token),
                )

            is BasicAuthAuthSetting ->
                staticCredential(
                    userId = userId,
                    setting = setting,
                    credentialType = CredentialType.BASIC_AUTH,
                    secret = setting.password,
                    data = mapOf("username" to setting.username, "password" to setting.password),
                )

            is OAuthDiscoverableAuthSetting,
            is OAuthRegisteredAuthSetting,
            is OAuthCustomAuthSetting,
            is OAuthMcpDiscoverableAuthSetting,
            -> {
                logger.debug {
                    "[StaticCredentialFactory] '${setting.name}' is ${setting.authType}: no static credential"
                }
                null
            }
        }

    private fun staticCredential(
        userId: UUID,
        setting: AuthSetting,
        credentialType: CredentialType,
        secret: String,
        data: Map<String, String>,
    ): Credential? {
        if (secret.isBlank()) {
            logger.warn {
                "[StaticCredentialFactory] AuthSetting '${setting.name}' (${setting.authType}) has a blank secret: " +
                    "no credential synthesised"
            }
            return null
        }
        return Credential(
            metadata = EntityMetadata(),
            userId = userId,
            authSettingId = setting.metadata.id,
            credentialType = credentialType,
            data = data,
        )
    }

    companion object : KLogging()
}
