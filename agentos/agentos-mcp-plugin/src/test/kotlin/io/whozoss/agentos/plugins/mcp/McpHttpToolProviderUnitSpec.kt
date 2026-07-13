package io.whozoss.agentos.plugins.mcp

import com.fasterxml.jackson.module.kotlin.jacksonObjectMapper
import io.kotest.core.spec.style.StringSpec
import io.kotest.matchers.shouldBe
import io.kotest.matchers.shouldNotBe
import io.mockk.every
import io.mockk.mockk
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

    // ── resolveBearerToken priority logic ────────────────────────────────────

    "resolveBearerToken returns null when context is null and no static token" {
        val config = McpServerConfig(url = "https://mcp.example.com")
        provider.resolveBearerToken(null, config) shouldBe null
    }

    "resolveBearerToken returns static authToken when no credential provider" {
        val config = McpServerConfig(url = "https://mcp.example.com", authToken = "static-token")
        provider.resolveBearerToken(null, config) shouldBe "static-token"
    }

    "resolveBearerToken returns static authToken when credential provider returns null" {
        val config = McpServerConfig(url = "https://mcp.example.com", authToken = "static-token")
        val credProvider: CredentialProvider = { null }
        val context = ToolContext(
            namespaceId = UUID.randomUUID(),
            userId = null,
            userExternalId = null,
            caseEvents = emptyList(),
            credentialProvider = credProvider,
        )
        provider.resolveBearerToken(context, config) shouldBe "static-token"
    }

    "resolveBearerToken returns null when no credential and no static token" {
        val config = McpServerConfig(url = "https://mcp.example.com")
        val credProvider: CredentialProvider = { null }
        val context = ToolContext(
            namespaceId = UUID.randomUUID(),
            userId = null,
            userExternalId = null,
            caseEvents = emptyList(),
            credentialProvider = credProvider,
        )
        provider.resolveBearerToken(context, config) shouldBe null
    }

    "resolveBearerToken prefers credential accessToken over static token" {
        val config = McpServerConfig(url = "https://mcp.example.com", authToken = "static-token")
        val credential = Credential(
            metadata = EntityMetadata(),
            userId = UUID.randomUUID(),
            authSettingId = UUID.randomUUID(),
            credentialType = CredentialType.OAUTH_TOKENS,
            data = mapOf("accessToken" to "oauth-access-token"),
        )
        val credProvider: CredentialProvider = { credential }
        val context = ToolContext(
            namespaceId = UUID.randomUUID(),
            userId = null,
            userExternalId = null,
            caseEvents = emptyList(),
            credentialProvider = credProvider,
        )
        val result = provider.resolveBearerToken(context, config)
        result shouldBe "oauth-access-token"
        result shouldNotBe "static-token"
    }

    "resolveBearerToken uses token key from credential" {
        val config = McpServerConfig(url = "https://mcp.example.com")
        val credential = Credential(
            metadata = EntityMetadata(),
            userId = UUID.randomUUID(),
            authSettingId = UUID.randomUUID(),
            credentialType = CredentialType.BEARER_TOKEN,
            data = mapOf("token" to "bearer-value"),
        )
        val credProvider: CredentialProvider = { credential }
        val context = ToolContext(
            namespaceId = UUID.randomUUID(),
            userId = null,
            userExternalId = null,
            caseEvents = emptyList(),
            credentialProvider = credProvider,
        )
        provider.resolveBearerToken(context, config) shouldBe "bearer-value"
    }

    "resolveBearerToken uses key from credential when accessToken and token are absent" {
        val config = McpServerConfig(url = "https://mcp.example.com")
        val credential = Credential(
            metadata = EntityMetadata(),
            userId = UUID.randomUUID(),
            authSettingId = UUID.randomUUID(),
            credentialType = CredentialType.API_KEY,
            data = mapOf("key" to "api-key-value"),
        )
        val credProvider: CredentialProvider = { credential }
        val context = ToolContext(
            namespaceId = UUID.randomUUID(),
            userId = null,
            userExternalId = null,
            caseEvents = emptyList(),
            credentialProvider = credProvider,
        )
        provider.resolveBearerToken(context, config) shouldBe "api-key-value"
    }

    "resolveBearerToken falls through to static token when credential has no recognised key" {
        val config = McpServerConfig(url = "https://mcp.example.com", authToken = "fallback")
        val credential = Credential(
            metadata = EntityMetadata(),
            userId = UUID.randomUUID(),
            authSettingId = UUID.randomUUID(),
            credentialType = CredentialType.BASIC_AUTH,
            data = mapOf("username" to "user", "password" to "pass"),
        )
        val credProvider: CredentialProvider = { credential }
        val context = ToolContext(
            namespaceId = UUID.randomUUID(),
            userId = null,
            userExternalId = null,
            caseEvents = emptyList(),
            credentialProvider = credProvider,
        )
        provider.resolveBearerToken(context, config) shouldBe "fallback"
    }
})
