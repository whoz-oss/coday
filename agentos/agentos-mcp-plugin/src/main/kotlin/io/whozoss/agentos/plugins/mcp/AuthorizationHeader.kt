package io.whozoss.agentos.plugins.mcp

import io.whozoss.agentos.sdk.credential.Credential
import io.whozoss.agentos.sdk.credential.CredentialType
import java.util.Base64

/**
 * Value of the HTTP `Authorization` header sent to a remote MCP server.
 *
 * The scheme is derived from the [CredentialType] alone (see [from]); the credential
 * material is read from the single key the SDK contract assigns to that type, never
 * by probing alternative key names.
 *
 * [toString] is overridden on every variant so that no credential material can leak
 * through logs, exception messages or debug output.
 */
sealed class AuthorizationHeader {

    /** Builds the exact header value to send on the wire. */
    abstract fun headerValue(): String

    /** `Authorization: Bearer <token>` (RFC 6750). */
    class Bearer(private val token: String) : AuthorizationHeader() {
        override fun headerValue(): String = "Bearer $token"

        override fun toString(): String = "AuthorizationHeader.Bearer"
    }

    /** `Authorization: Basic base64(username ":" password)` (RFC 7617, UTF-8). */
    class Basic(private val username: String, private val password: String) : AuthorizationHeader() {
        override fun headerValue(): String {
            val userPass = "$username:$password".toByteArray(Charsets.UTF_8)
            return "Basic ${Base64.getEncoder().encodeToString(userPass)}"
        }

        override fun toString(): String = "AuthorizationHeader.Basic(username=***)"
    }

    companion object {
        /**
         * Maps a [Credential] to the header its [CredentialType] dictates, or `null` when
         * the credential carries no usable material (required key missing or blank).
         *
         * - [CredentialType.OAUTH_TOKENS] → [Bearer] with `accessToken`
         * - [CredentialType.BEARER_TOKEN] → [Bearer] with `token`
         * - [CredentialType.API_KEY] → [Bearer] with `key` (kept as Bearer to preserve the behaviour MCP_HTTP
         *   had before credential-type-driven mapping; the MCP transport has no API-key header convention)
         * - [CredentialType.BASIC_AUTH] → [Basic] with `username` and `password`
         */
        fun from(credential: Credential): AuthorizationHeader? = when (credential.credentialType) {
            CredentialType.OAUTH_TOKENS -> bearer(credential, "accessToken")
            CredentialType.BEARER_TOKEN -> bearer(credential, "token")
            CredentialType.API_KEY -> bearer(credential, "key")
            CredentialType.BASIC_AUTH -> basic(credential)
        }

        private fun bearer(credential: Credential, key: String): Bearer? =
            credential.material(key)?.let { Bearer(it) }

        private fun basic(credential: Credential): Basic? {
            val username = credential.material("username") ?: return null
            val password = credential.material("password") ?: return null
            return Basic(username = username, password = password)
        }

        private fun Credential.material(key: String): String? = data[key]?.takeIf { it.isNotBlank() }
    }
}
