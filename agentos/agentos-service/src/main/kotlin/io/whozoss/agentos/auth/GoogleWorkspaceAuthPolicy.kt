package io.whozoss.agentos.auth

import io.whozoss.agentos.authSetting.AuthSetting
import io.whozoss.agentos.authSetting.OAuthRegisteredAuthSetting

/** Guard applied before the native credential supplier can refresh or authorize. */
object GoogleWorkspaceAuthPolicy {
    fun tokenClient(client: okhttp3.OkHttpClient, endpoint: String): okhttp3.OkHttpClient = when (endpoint) {
        "https://oauth2.googleapis.com/token" -> client.newBuilder().followRedirects(false).followSslRedirects(false)
            .retryOnConnectionFailure(false).callTimeout(20, java.util.concurrent.TimeUnit.SECONDS).build()
        else -> client
    }
    private val scopes = setOf(
        "https://www.googleapis.com/auth/drive.readonly",
        "https://www.googleapis.com/auth/calendar.readonly",
        "https://www.googleapis.com/auth/gmail.readonly",
    )
    fun requireGrant(setting: AuthSetting, credential: io.whozoss.agentos.sdk.credential.Credential, userId: java.util.UUID) {
        check(allowed(setting) && credential.userId == userId && credential.authSettingId == setting.metadata.id &&
            credential.credentialType == io.whozoss.agentos.sdk.credential.CredentialType.OAUTH_TOKENS &&
            allowedGrantScope(setting, credential.data["scope"])) {
            "Governed Google OAuth grant scope is unknown or disallowed; reconnect with read-only consent"
        }
    }

    private fun allowedGrantScope(setting: AuthSetting, actual: String?): Boolean {
        val requested = (setting as? OAuthRegisteredAuthSetting)?.scopes ?: return false
        return !actual.isNullOrBlank() && actual.split(Regex("\\s+")).all { it in scopes && it in requested.split(Regex("\\s+")) }
    }

    fun allowed(setting: AuthSetting): Boolean = when (setting) {
        is OAuthRegisteredAuthSetting -> setting.authorizationUrl == "https://accounts.google.com/o/oauth2/v2/auth" &&
            setting.tokenUrl == "https://oauth2.googleapis.com/token" && setting.clientId.isNotBlank() &&
            !setting.scopes.isNullOrBlank() && setting.scopes.split(Regex("\\s+")).all { it in scopes }
        else -> false
    }
}
