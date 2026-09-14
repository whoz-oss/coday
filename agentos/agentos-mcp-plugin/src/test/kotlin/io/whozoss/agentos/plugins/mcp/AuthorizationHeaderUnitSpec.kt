package io.whozoss.agentos.plugins.mcp

import io.kotest.core.spec.IsolationMode
import io.kotest.core.spec.style.StringSpec
import io.kotest.matchers.shouldBe
import io.kotest.matchers.string.shouldContain
import io.kotest.matchers.string.shouldNotContain
import io.kotest.matchers.types.shouldBeInstanceOf
import io.whozoss.agentos.sdk.credential.Credential
import io.whozoss.agentos.sdk.credential.CredentialType
import java.util.UUID

class AuthorizationHeaderUnitSpec : StringSpec({

    isolationMode = IsolationMode.InstancePerLeaf

    fun credential(type: CredentialType, data: Map<String, String>): Credential = Credential(
        userId = UUID.randomUUID(),
        authSettingId = UUID.randomUUID(),
        credentialType = type,
        data = data,
    )

    // One entry per credential type: the material carried and the exact header expected.
    // A new CredentialType value has no entry here and makes the parameterised block fail.
    val expectedByType: Map<CredentialType, Pair<Map<String, String>, String>> = mapOf(
        CredentialType.API_KEY to (mapOf("key" to "api-key-value") to "Bearer api-key-value"),
        CredentialType.BEARER_TOKEN to (mapOf("token" to "bearer-value") to "Bearer bearer-value"),
        CredentialType.OAUTH_TOKENS to (mapOf("accessToken" to "oauth-access") to "Bearer oauth-access"),
        // base64("support@corp.com/token:s3cr3t") computed with an independent tool
        CredentialType.BASIC_AUTH to (
            mapOf("username" to "support@corp.com/token", "password" to "s3cr3t")
                to "Basic c3VwcG9ydEBjb3JwLmNvbS90b2tlbjpzM2NyM3Q="
            ),
    )

    // Alternative key names that the legacy probing accepted; none of them must be honoured now.
    val foreignKeys = mapOf("apiKey" to "x", "accessToken" to "x", "token" to "x", "key" to "x")

    CredentialType.entries.forEach { type ->
        "from maps $type to the expected Authorization header" {
            val (data, expectedHeader) = expectedByType.getValue(type)
            AuthorizationHeader.from(credential(type, data))?.headerValue() shouldBe expectedHeader
        }

        "from returns null for $type when the required material is missing" {
            AuthorizationHeader.from(credential(type, emptyMap())) shouldBe null
        }

        "from returns null for $type when the required material is blank" {
            val (data, _) = expectedByType.getValue(type)
            val blanked = data.mapValues { "  " }
            AuthorizationHeader.from(credential(type, blanked)) shouldBe null
        }

        "from does not probe alternative key names for $type" {
            val (data, _) = expectedByType.getValue(type)
            // Everything the contract requires except the last piece of material, so that the only
            // way to build a header is to read that piece from one of the foreign keys.
            val allButLast = data.toList().dropLast(1).toMap()
            val withForeignKeys = allButLast + foreignKeys.filterKeys { it !in data.keys }
            AuthorizationHeader.from(credential(type, withForeignKeys)) shouldBe null
        }
    }

    "from returns Bearer for API_KEY" {
        AuthorizationHeader.from(credential(CredentialType.API_KEY, mapOf("key" to "k")))
            .shouldBeInstanceOf<AuthorizationHeader.Bearer>()
    }

    "from returns Basic for BASIC_AUTH" {
        AuthorizationHeader.from(credential(CredentialType.BASIC_AUTH, mapOf("username" to "u", "password" to "p")))
            .shouldBeInstanceOf<AuthorizationHeader.Basic>()
    }

    "BASIC_AUTH with only a username is unusable" {
        AuthorizationHeader.from(credential(CredentialType.BASIC_AUTH, mapOf("username" to "u"))) shouldBe null
    }

    "BASIC_AUTH with only a password is unusable" {
        AuthorizationHeader.from(credential(CredentialType.BASIC_AUTH, mapOf("password" to "p"))) shouldBe null
    }

    "Bearer toString never reveals the token" {
        val rendered = AuthorizationHeader.Bearer("top-secret-token").toString()
        rendered shouldNotContain "top-secret-token"
        rendered shouldContain "Bearer"
    }

    "Basic toString never reveals the username or the password" {
        val rendered = AuthorizationHeader.Basic(username = "alice", password = "wonderland").toString()
        rendered shouldNotContain "alice"
        rendered shouldNotContain "wonderland"
        rendered shouldNotContain "YWxpY2U6d29uZGVybGFuZA=="
        rendered shouldContain "Basic"
    }
})
