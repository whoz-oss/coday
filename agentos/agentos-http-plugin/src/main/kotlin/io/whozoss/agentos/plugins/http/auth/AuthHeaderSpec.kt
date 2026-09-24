package io.whozoss.agentos.plugins.http.auth

import io.whozoss.agentos.plugins.http.config.ApiKeyPlacement
import io.whozoss.agentos.plugins.http.config.AuthConfig
import io.whozoss.agentos.sdk.credential.Credential
import io.whozoss.agentos.sdk.credential.CredentialType
import java.util.Base64

/**
 * How one run authenticates its calls, resolved once from the credential of the bound Auth Setting.
 *
 * The service injects a credential provider only when the integration config names an `authSettingName`
 * and it can build one for the run (a user identity, a credential for that user). When it cannot, the
 * plugin receives no provider and cannot tell that apart from an unbound config: the spec is [None], the
 * same tools are listed and calls are sent unauthenticated, typically ending in `UNAUTHORIZED`. An
 * authenticated config therefore needs a run with a user identity. Only a provider that is present but
 * yields nothing usable is [Missing]: the tools are still listed but every call fails fast without a
 * request. `toString` of every variant is redacted.
 */
sealed interface AuthHeaderSpec {

    /** No credential provider on the run (no Auth Setting bound, or none could be built): unauthenticated calls. */
    data object None : AuthHeaderSpec

    data class Header(val name: String, val value: String) : AuthHeaderSpec {
        override fun toString(): String = "Header($name: <redacted>)"
    }

    data class Query(val name: String, val value: String) : AuthHeaderSpec {
        override fun toString(): String = "Query($name=<redacted>)"
    }

    /** An Auth Setting is bound but no usable credential could be resolved; [reason] is safe to surface. */
    data class Missing(val reason: String) : AuthHeaderSpec

    companion object {

        /**
         * @param providerBound True when the tool context carried a credential provider, i.e. the config is
         *   bound to an Auth Setting.
         */
        fun from(credential: Credential?, auth: AuthConfig, providerBound: Boolean): AuthHeaderSpec {
            if (!providerBound) return None
            if (credential == null) return Missing("no credential available for the bound Auth Setting")
            return when (credential.credentialType) {
                CredentialType.OAUTH_TOKENS -> bearer(credential, "accessToken")
                CredentialType.BEARER_TOKEN -> bearer(credential, "token")
                CredentialType.BASIC_AUTH -> basic(credential)
                CredentialType.API_KEY -> apiKey(credential, auth)
            }
        }

        private fun bearer(credential: Credential, key: String): AuthHeaderSpec =
            material(credential, key)?.let { Header(name = AUTHORIZATION, value = "Bearer $it") } ?: missing(credential)

        /** RFC 7617: `base64(username:password)` over UTF-8; the username may itself contain `/` or `@`. */
        private fun basic(credential: Credential): AuthHeaderSpec {
            val username = material(credential, "username") ?: return missing(credential)
            val password = material(credential, "password") ?: return missing(credential)
            val encoded = Base64.getEncoder().encodeToString("$username:$password".toByteArray(Charsets.UTF_8))
            return Header(name = AUTHORIZATION, value = "Basic $encoded")
        }

        private fun apiKey(credential: Credential, auth: AuthConfig): AuthHeaderSpec {
            val key = material(credential, "key") ?: return missing(credential)
            return when (auth.apiKeyIn) {
                ApiKeyPlacement.HEADER -> Header(name = auth.apiKeyName, value = key)
                ApiKeyPlacement.QUERY -> Query(name = auth.apiKeyName, value = key)
                ApiKeyPlacement.BEARER -> Header(name = AUTHORIZATION, value = "Bearer $key")
            }
        }

        private fun material(credential: Credential, key: String): String? =
            credential.data[key]?.takeIf { it.isNotBlank() }

        private fun missing(credential: Credential): Missing =
            Missing("credential of type ${credential.credentialType} carries no usable material")

        private const val AUTHORIZATION = "Authorization"
    }
}
