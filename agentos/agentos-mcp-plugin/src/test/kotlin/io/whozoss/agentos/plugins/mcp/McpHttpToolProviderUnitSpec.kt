package io.whozoss.agentos.plugins.mcp

import ch.qos.logback.classic.Level
import com.fasterxml.jackson.module.kotlin.jacksonObjectMapper
import io.kotest.core.spec.style.StringSpec
import io.kotest.matchers.collections.shouldBeEmpty
import io.kotest.matchers.collections.shouldHaveSize
import io.kotest.matchers.shouldBe
import io.kotest.matchers.string.shouldContain
import io.kotest.matchers.string.shouldNotContain
import io.kotest.matchers.types.shouldBeInstanceOf
import io.whozoss.agentos.sdk.auth.CredentialProvider
import io.whozoss.agentos.sdk.credential.Credential
import io.whozoss.agentos.sdk.credential.CredentialType
import io.whozoss.agentos.sdk.entity.EntityMetadata
import io.whozoss.agentos.sdk.tool.ToolContext
import java.util.UUID

class McpHttpToolProviderUnitSpec : StringSpec({

    val provider = McpHttpToolProvider()
    val mapper = jacksonObjectMapper()

    val ctx = ToolContext(UUID.randomUUID(), null, null, emptyList())

    // ── provideTools config validation ────────────────────────────────────────

    "provideTools returns empty list when config is null" {
        provider.provideTools(null, "test", ctx) shouldBe emptyList()
    }

    "provideTools returns empty list when config is JSON null" {
        val nullNode = mapper.readTree("null")
        provider.provideTools(nullNode, "test", ctx) shouldBe emptyList()
    }

    "provideTools returns empty list when config is invalid JSON" {
        val invalid = mapper.readTree("{}")
        provider.provideTools(invalid, "test", ctx) shouldBe emptyList()
    }

    "provideTools returns empty list when config has stdio command instead of url" {
        val stdioConfig = mapper.readTree("""{ "command": "docker" }""")
        // McpConfigParser.parse will succeed (it's a valid STDIO config), but transport != HTTP
        provider.provideTools(stdioConfig, "test", ctx) shouldBe emptyList()
    }

    // ── resolveAuthorization priority chain ──────────────────────────────────

    fun credential(type: CredentialType, data: Map<String, String>): Credential = Credential(
        metadata = EntityMetadata(),
        userId = UUID.randomUUID(),
        authSettingId = UUID.randomUUID(),
        credentialType = type,
        data = data,
    )

    fun contextWith(credProvider: CredentialProvider): ToolContext = ToolContext(
        namespaceId = UUID.randomUUID(),
        userId = null,
        userExternalId = null,
        caseEvents = emptyList(),
        credentialProvider = credProvider,
    )

    "resolveAuthorization returns null when context is null and no static token" {
        val config = McpServerConfig(url = "https://mcp.example.com")
        provider.resolveAuthorization(null, config) shouldBe null
    }

    "resolveAuthorization returns Bearer static authToken when no credential provider" {
        val config = McpServerConfig(url = "https://mcp.example.com", authToken = "static-token")
        provider.resolveAuthorization(null, config)?.headerValue() shouldBe "Bearer static-token"
    }

    "resolveAuthorization returns Bearer static authToken when credential provider returns null" {
        val config = McpServerConfig(url = "https://mcp.example.com", authToken = "static-token")
        val context = contextWith { null }
        provider.resolveAuthorization(context, config)?.headerValue() shouldBe "Bearer static-token"
    }

    "resolveAuthorization returns null when no credential and no static token" {
        val config = McpServerConfig(url = "https://mcp.example.com")
        val context = contextWith { null }
        provider.resolveAuthorization(context, config) shouldBe null
    }

    "resolveAuthorization prefers the credential over the static token" {
        val config = McpServerConfig(url = "https://mcp.example.com", authToken = "static-token")
        val context = contextWith { credential(CredentialType.OAUTH_TOKENS, mapOf("accessToken" to "oauth-access")) }
        val result = provider.resolveAuthorization(context, config)
        result.shouldBeInstanceOf<AuthorizationHeader.Bearer>()
        result.headerValue() shouldBe "Bearer oauth-access"
    }

    "resolveAuthorization maps a BASIC_AUTH credential to a Basic header" {
        val config = McpServerConfig(url = "https://mcp.example.com", authToken = "static-token")
        val context = contextWith { credential(CredentialType.BASIC_AUTH, mapOf("username" to "u", "password" to "p")) }
        val result = provider.resolveAuthorization(context, config)
        result.shouldBeInstanceOf<AuthorizationHeader.Basic>()
        result.headerValue() shouldBe "Basic dTpw"
    }

    "resolveAuthorization falls back to the static token when the credential carries no usable material" {
        val config = McpServerConfig(url = "https://mcp.example.com", authToken = "fallback")
        val context = contextWith { credential(CredentialType.BEARER_TOKEN, mapOf("apiKey" to "wrong-key-name")) }
        provider.resolveAuthorization(context, config)?.headerValue() shouldBe "Bearer fallback"
    }

    "resolveAuthorization returns null when the credential is unusable and no static token" {
        val config = McpServerConfig(url = "https://mcp.example.com")
        val context = contextWith { credential(CredentialType.API_KEY, emptyMap()) }
        provider.resolveAuthorization(context, config) shouldBe null
    }

    // ── logging ───────────────────────────────────────────────────────────────

    "resolveAuthorization warns that the static authToken is deprecated, without echoing it" {
        val config = McpServerConfig(url = "https://mcp.example.com", authToken = "static-token")
        val logs = LogCapture.capturing { provider.resolveAuthorization(null, config) }
        val warnings = logs.messagesAt(Level.WARN)
        warnings shouldHaveSize 1
        warnings.single() shouldContain "deprecated"
        warnings.single() shouldContain "https://mcp.example.com"
        logs.messages.forEach { it shouldNotContain "static-token" }
    }

    "resolveAuthorization does not warn when a credential is used, and never logs its material" {
        val config = McpServerConfig(url = "https://mcp.example.com", authToken = "static-token")
        val context = contextWith { credential(CredentialType.BASIC_AUTH, mapOf("username" to "u", "password" to "p")) }
        val logs = LogCapture.capturing { provider.resolveAuthorization(context, config) }
        logs.messagesAt(Level.WARN).shouldBeEmpty()
        logs.messages.forEach {
            it shouldNotContain "static-token"
            it shouldNotContain "dTpw"
        }
    }

    "resolveAuthorization warns when the credential carries no usable material, without echoing config" {
        val config = McpServerConfig(url = "https://mcp.example.com", authToken = "static-token")
        val context = contextWith { credential(CredentialType.BEARER_TOKEN, mapOf("apiKey" to "wrong-key-name")) }
        val logs = LogCapture.capturing { provider.resolveAuthorization(context, config) }
        val warnings = logs.messagesAt(Level.WARN)
        warnings shouldHaveSize 2
        warnings[0] shouldContain "no usable material"
        warnings[1] shouldContain "deprecated"
        logs.messages.forEach {
            it shouldNotContain "static-token"
            it shouldNotContain "wrong-key-name"
        }
    }
})
