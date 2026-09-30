package io.whozoss.agentos.plugins.http.auth

import io.kotest.core.spec.IsolationMode
import io.kotest.core.spec.style.StringSpec
import io.kotest.matchers.shouldBe
import io.kotest.matchers.string.shouldContain
import io.kotest.matchers.string.shouldNotContain
import io.kotest.matchers.types.shouldBeInstanceOf
import io.whozoss.agentos.plugins.http.config.ApiKeyPlacement
import io.whozoss.agentos.plugins.http.config.AuthConfig
import io.whozoss.agentos.sdk.credential.Credential
import io.whozoss.agentos.sdk.credential.CredentialType
import java.util.UUID

class AuthHeaderSpecUnitSpec : StringSpec({
    isolationMode = IsolationMode.InstancePerLeaf

    fun credential(type: CredentialType, data: Map<String, String>): Credential =
        Credential(userId = UUID.randomUUID(), authSettingId = UUID.randomUUID(), credentialType = type, data = data)

    val material: Map<CredentialType, Map<String, String>> = mapOf(
        CredentialType.OAUTH_TOKENS to mapOf("accessToken" to "oauth-access", "refreshToken" to "oauth-refresh"),
        CredentialType.BEARER_TOKEN to mapOf("token" to "bearer-token"),
        CredentialType.BASIC_AUTH to mapOf("username" to "support@corp.com/token", "password" to "s3cr3t"),
        CredentialType.API_KEY to mapOf("key" to "api-key-value"),
    )

    val expected: Map<CredentialType, AuthHeaderSpec> = mapOf(
        CredentialType.OAUTH_TOKENS to AuthHeaderSpec.Header("Authorization", "Bearer oauth-access"),
        CredentialType.BEARER_TOKEN to AuthHeaderSpec.Header("Authorization", "Bearer bearer-token"),
        CredentialType.BASIC_AUTH to
            AuthHeaderSpec.Header("Authorization", "Basic c3VwcG9ydEBjb3JwLmNvbS90b2tlbjpzM2NyM3Q="),
        CredentialType.API_KEY to AuthHeaderSpec.Header("X-API-Key", "api-key-value"),
    )

    CredentialType.entries.forEach { type ->
        "maps a $type credential with the default API key placement" {
            val data = checkNotNull(material[type]) { "no test material for $type: extend the matrix" }
            AuthHeaderSpec.from(credential(type, data), AuthConfig(), providerBound = true) shouldBe expected[type]
        }

        "a $type credential without material is Missing and names the type, never the values" {
            val spec = AuthHeaderSpec.from(credential(type, mapOf("other" to "x")), AuthConfig(), providerBound = true)
            val reason = spec.shouldBeInstanceOf<AuthHeaderSpec.Missing>().reason
            reason shouldContain type.name
            reason shouldContain "no usable material"
        }
    }

    "a blank API key is Missing" {
        val blank = credential(CredentialType.API_KEY, mapOf("key" to "  "))
        AuthHeaderSpec.from(blank, AuthConfig(), providerBound = true).shouldBeInstanceOf<AuthHeaderSpec.Missing>()
    }

    "places the API key in a query parameter when configured" {
        val auth = AuthConfig(apiKeyIn = ApiKeyPlacement.QUERY, apiKeyName = "api_key")
        AuthHeaderSpec.from(credential(CredentialType.API_KEY, mapOf("key" to "k")), auth, providerBound = true)
            .shouldBe(AuthHeaderSpec.Query("api_key", "k"))
    }

    "places the API key as a Bearer token when configured" {
        val auth = AuthConfig(apiKeyIn = ApiKeyPlacement.BEARER, apiKeyName = "ignored")
        AuthHeaderSpec.from(credential(CredentialType.API_KEY, mapOf("key" to "k")), auth, providerBound = true)
            .shouldBe(AuthHeaderSpec.Header("Authorization", "Bearer k"))
    }

    "an OAuth credential ignores the API key placement" {
        val auth = AuthConfig(apiKeyIn = ApiKeyPlacement.QUERY)
        val data = mapOf("accessToken" to "t")
        AuthHeaderSpec.from(credential(CredentialType.OAUTH_TOKENS, data), auth, providerBound = true)
            .shouldBe(AuthHeaderSpec.Header("Authorization", "Bearer t"))
    }

    "a bound provider without credential is Missing" {
        val reason = AuthHeaderSpec.from(null, AuthConfig(), providerBound = true)
            .shouldBeInstanceOf<AuthHeaderSpec.Missing>().reason
        reason shouldContain "no credential available"
    }

    "no bound provider is None" {
        AuthHeaderSpec.from(null, AuthConfig(), providerBound = false) shouldBe AuthHeaderSpec.None
    }

    "toString never reveals the value" {
        val header = AuthHeaderSpec.Header("Authorization", "Bearer super-secret").toString()
        header shouldNotContain "super-secret"
        header shouldContain "Authorization"
        val query = AuthHeaderSpec.Query("api_key", "super-secret").toString()
        query shouldNotContain "super-secret"
        query shouldContain "api_key"
    }
})
