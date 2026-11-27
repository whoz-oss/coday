package io.whozoss.agentos.auth

import com.fasterxml.jackson.databind.ObjectMapper
import io.kotest.assertions.throwables.shouldThrow
import io.kotest.core.spec.style.StringSpec
import io.kotest.matchers.shouldBe
import io.mockk.every
import io.mockk.mockk
import io.mockk.verify
import io.whozoss.agentos.authSetting.*
import io.whozoss.agentos.credential.CredentialService
import io.whozoss.agentos.sdk.credential.Credential
import io.whozoss.agentos.sdk.credential.CredentialType
import okhttp3.OkHttpClient
import okhttp3.ResponseBody.Companion.toResponseBody
import java.time.Instant
import java.util.UUID

class ConnectionPolicyLifecycleUnitSpec : StringSpec({
    "general policy rejects a broad saved grant before refresh or outbound" {
        val user = UUID.randomUUID()
        val setting = OAuthRegisteredAuthSetting(name = "READ", clientId = "synthetic", authorizationUrl = "https://auth.example.test/auth",
            tokenUrl = "https://auth.example.test/token", scopes = "read")
        val registry = ConnectionPolicyRegistry(ConnectionPolicyProperties(policies = mapOf("read" to ConnectionPolicy(
            allowedAuthTypes = setOf(AuthType.OAUTH_REGISTERED), authorizationUrls = setOf(setting.authorizationUrl),
            tokenUrls = setOf(setting.tokenUrl), allowedScopes = setOf("read"))), authSettingNames = mapOf("READ" to setOf("read"))))
        val creds = mockk<CredentialService>()
        every { creds.resolve(user, setting.metadata.id) } returns Credential(userId = user, authSettingId = setting.metadata.id,
            credentialType = CredentialType.OAUTH_TOKENS, data = mapOf("scope" to "write", "refreshToken" to "synthetic", "expiresAt" to Instant.EPOCH.toString()))
        var outbound = 0
        val client = OkHttpClient.Builder().addInterceptor { outbound++; error("Unexpected outbound") }.build()
        val flow = OAuthFlowService(mockk(), creds, client, ObjectMapper(), OAuthConfigProperties(redirectUri = ""), registry)
        try {
            shouldThrow<IllegalStateException> { flow.resolveOAuthCredential(user, setting, UUID.randomUUID(), UUID.randomUUID(), UUID.randomUUID(), "agent", { it }) }
            outbound shouldBe 0
            verify(exactly = 0) { creds.store(any()) }
        } finally { flow.shutdown(); client.dispatcher.executorService.shutdown(); client.connectionPool.evictAll() }
    }
    "OAuth rejects changed effective settings before refresh storage" {
        val user = UUID.randomUUID()
        val ns = UUID.randomUUID()
        val setting = OAuthRegisteredAuthSetting(name = "READ", clientId = "synthetic", authorizationUrl = "https://auth.example.test/auth", tokenUrl = "https://auth.example.test/token", scopes = "read")
        val registry = ConnectionPolicyRegistry(ConnectionPolicyProperties(policies = mapOf("read" to ConnectionPolicy(allowedAuthTypes = setOf(AuthType.OAUTH_REGISTERED), authorizationUrls = setOf(setting.authorizationUrl), tokenUrls = setOf(setting.tokenUrl), allowedScopes = setOf("read"))), authSettingNames = mapOf("READ" to setOf("read"))))
        val creds = mockk<CredentialService>()
        val settings = mockk<AuthSettingService>()
        var effective = setting
        every { settings.resolveAuthSetting(ns, user, "READ") } answers { effective }
        every { creds.resolve(user, setting.metadata.id) } returns Credential(userId = user, authSettingId = setting.metadata.id, credentialType = CredentialType.OAUTH_TOKENS, data = mapOf("scope" to "read", "refreshToken" to "synthetic", "expiresAt" to Instant.EPOCH.toString()))
        val client = OkHttpClient.Builder().addInterceptor { chain ->
            effective = setting.copy(tokenUrl = "https://attacker.example.test/token")
            okhttp3.Response.Builder().request(chain.request()).protocol(okhttp3.Protocol.HTTP_1_1).code(200).message("OK").body("""{"access_token":"synthetic","scope":"read"}""".toResponseBody()).build()
        }.build()
        val flow = OAuthFlowService(mockk(), creds, client, ObjectMapper(), OAuthConfigProperties(redirectUri = ""), registry, settings)
        try {
            shouldThrow<IllegalStateException> { flow.resolveOAuthCredential(user, setting, ns, UUID.randomUUID(), UUID.randomUUID(), "agent", { it }) }
            verify(exactly = 0) { creds.store(any()) }
        } finally { flow.shutdown(); client.dispatcher.executorService.shutdown(); client.connectionPool.evictAll() }
    }
    "discovery endpoint redirect is denied before token exchange or storage" {
        val user = UUID.randomUUID()
        val setting = OAuthDiscoverableAuthSetting(name = "READ", clientId = "synthetic", discoveryUrl = "https://auth.example.test/discovery", scopes = "read")
        val policy = ConnectionPolicy(allowedAuthTypes = setOf(AuthType.OAUTH_DISCOVERABLE), discoveryUrls = setOf(setting.discoveryUrl), authorizationUrls = setOf("https://auth.example.test/auth"), tokenUrls = setOf("https://auth.example.test/token"), allowedScopes = setOf("read"))
        val creds = mockk<CredentialService>()
        every { creds.resolve(user, setting.metadata.id) } returns null
        var requests = 0
        val client = OkHttpClient.Builder().addInterceptor { chain ->
            requests++
            chain.request().url.toString() shouldBe setting.discoveryUrl
            okhttp3.Response.Builder().request(chain.request()).protocol(okhttp3.Protocol.HTTP_1_1).code(200).message("OK")
                .body("""{"authorization_endpoint":"https://auth.example.test/auth","token_endpoint":"https://attacker.example.test/token"}""".toResponseBody()).build()
        }.build()
        val flow = OAuthFlowService(mockk(), creds, client, ObjectMapper(), OAuthConfigProperties(redirectUri = "https://app.example.test/callback"), ConnectionPolicyRegistry(ConnectionPolicyProperties(policies = mapOf("read" to policy))))
        try {
            shouldThrow<IllegalStateException> { flow.resolveOAuthCredential(user, setting, UUID.randomUUID(), UUID.randomUUID(), UUID.randomUUID(), "agent", { it }) }
            requests shouldBe 1
            verify(exactly = 0) { creds.store(any()) }
        } finally { flow.shutdown(); client.dispatcher.executorService.shutdown(); client.connectionPool.evictAll() }
    }
})
