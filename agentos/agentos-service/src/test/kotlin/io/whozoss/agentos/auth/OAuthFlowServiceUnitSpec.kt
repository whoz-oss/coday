package io.whozoss.agentos.auth

import com.fasterxml.jackson.databind.ObjectMapper
import io.kotest.core.spec.style.StringSpec
import io.kotest.matchers.nulls.shouldBeNull
import io.kotest.matchers.nulls.shouldNotBeNull
import io.kotest.matchers.shouldBe
import io.kotest.matchers.string.shouldContain
import io.mockk.clearAllMocks
import io.mockk.every
import io.mockk.mockk
import io.mockk.slot
import io.mockk.verify
import io.whozoss.agentos.credential.CredentialService
import io.whozoss.agentos.sdk.authSetting.OAuthDiscoverableAuthSetting
import io.whozoss.agentos.sdk.authSetting.OAuthRegisteredAuthSetting
import io.whozoss.agentos.sdk.caseEvent.CaseEvent
import io.whozoss.agentos.sdk.caseEvent.QuestionEvent
import io.whozoss.agentos.sdk.caseEvent.QuestionType
import io.whozoss.agentos.sdk.credential.Credential
import io.whozoss.agentos.sdk.credential.CredentialType
import io.whozoss.agentos.sdk.entity.EntityMetadata
import kotlinx.coroutines.runBlocking
import okhttp3.OkHttpClient
import okhttp3.Protocol
import okhttp3.Response
import okhttp3.ResponseBody.Companion.toResponseBody
import java.time.Instant
import java.util.UUID
import java.util.concurrent.CompletableFuture

class OAuthFlowServiceUnitSpec : StringSpec({
    val objectMapper = ObjectMapper()
    val credentialService = mockk<CredentialService>()
    val pendingRegistry = mockk<OAuthPendingRegistry>()
    val httpClient = mockk<OkHttpClient>()
    val userId = UUID.randomUUID()
    val namespaceId = UUID.randomUUID()
    val caseId = UUID.randomUUID()
    val agentId = UUID.randomUUID()
    val authSettingId = UUID.randomUUID()
    val emitEvent: (CaseEvent) -> CaseEvent = { it }

    fun config(redirectUri: String = "https://app.example.com/oauth/callback") =
        OAuthConfigProperties(redirectUri = redirectUri)

    fun service(redirectUri: String = "https://app.example.com/oauth/callback") =
        OAuthFlowService(pendingRegistry, credentialService, httpClient, objectMapper, config(redirectUri))

    fun registeredSetting() = OAuthRegisteredAuthSetting(
        metadata = EntityMetadata(id = authSettingId), name = "test-oauth", clientId = "client-id",
        clientSecret = "client-secret", authorizationUrl = "https://provider.example.com/auth",
        tokenUrl = "https://provider.example.com/token", scopes = "read write",
    )

    fun discoverableSetting() = OAuthDiscoverableAuthSetting(
        metadata = EntityMetadata(id = authSettingId), name = "test-discoverable", clientId = "client-id",
        clientSecret = "client-secret", discoveryUrl = "https://provider.example.com/.well-known/openid-configuration",
        scopes = "openid profile",
    )

    fun validCredential() = Credential(
        metadata = EntityMetadata(), userId = userId, authSettingId = authSettingId,
        credentialType = CredentialType.OAUTH_TOKENS,
        data = mapOf("accessToken" to "valid-token", "refreshToken" to "refresh-token", "expiresAt" to Instant.now().plusSeconds(3600).toString()),
    )

    fun expiredCredential(withRefreshToken: Boolean = true) = Credential(
        metadata = EntityMetadata(), userId = userId, authSettingId = authSettingId,
        credentialType = CredentialType.OAUTH_TOKENS,
        data = mapOf("accessToken" to "expired-token", "refreshToken" to if (withRefreshToken) "refresh-token" else "", "expiresAt" to Instant.now().minusSeconds(3600).toString()),
    )

    fun mockCall(body: String, code: Int = 200): okhttp3.Call {
        val resp = Response.Builder().request(okhttp3.Request.Builder().url("https://example.com").build()).protocol(Protocol.HTTP_1_1).code(code).message("OK").body(body.toResponseBody()).build()
        val call = mockk<okhttp3.Call>()
        every { call.execute() } returns resp
        return call
    }

    beforeTest { clearAllMocks() }

    "returns existing credential when not expired" {
        val cred = validCredential()
        every { credentialService.resolve(userId, authSettingId) } returns cred
        val result = runBlocking { service().resolveOAuthCredential(userId, registeredSetting(), namespaceId, caseId, agentId, "agent", emitEvent) }
        result shouldBe cred
        verify(exactly = 0) { httpClient.newCall(any()) }
    }

    "refreshes expired token when refresh token is available" {
        val tokenJson = """{"access_token":"new-token","expires_in":3600,"token_type":"Bearer"}"""
        every { credentialService.resolve(userId, authSettingId) } returns expiredCredential(withRefreshToken = true)
        every { httpClient.newCall(any()) } returns mockCall(tokenJson)
        val slot = slot<Credential>()
        every { credentialService.store(capture(slot)) } answers { slot.captured }
        val result = runBlocking { service().resolveOAuthCredential(userId, registeredSetting(), namespaceId, caseId, agentId, "agent", emitEvent) }
        result.shouldNotBeNull()
        result.data["accessToken"] shouldBe "new-token"
        verify(exactly = 1) { httpClient.newCall(any()) }
    }

    "triggers interactive flow when no credential exists" {
        val tokenJson = """{"access_token":"new-token","refresh_token":"rt","expires_in":3600}"""
        every { credentialService.resolve(userId, authSettingId) } returns null
        every { pendingRegistry.register(any()) } returns CompletableFuture.completedFuture("auth-code")
        every { httpClient.newCall(any()) } returns mockCall(tokenJson)
        val slot = slot<Credential>()
        every { credentialService.store(capture(slot)) } answers { slot.captured }
        val emitted = mutableListOf<CaseEvent>()
        val result = runBlocking { service().resolveOAuthCredential(userId, registeredSetting(), namespaceId, caseId, agentId, "agent") { emitted += it; it } }
        result.shouldNotBeNull()
        result.data["accessToken"] shouldBe "new-token"
        emitted.size shouldBe 1
        val q = emitted[0] as QuestionEvent
        q.questionType shouldBe QuestionType.OAUTH_AUTHORIZE
        q.question shouldContain "https://provider.example.com/auth"
        q.question shouldContain "code_challenge_method=S256"
    }

    "returns null on flow cancellation" {
        every { credentialService.resolve(userId, authSettingId) } returns null
        every { pendingRegistry.register(any()) } answers { val f = CompletableFuture<String>(); f.cancel(true); f }
        val result = runBlocking { service().resolveOAuthCredential(userId, registeredSetting(), namespaceId, caseId, agentId, "agent", emitEvent) }
        result.shouldBeNull()
    }

    "discovers endpoints for OAUTH_DISCOVERABLE" {
        val discoveryJson = """{"authorization_endpoint":"https://provider.example.com/auth","token_endpoint":"https://provider.example.com/token"}"""
        val tokenJson = """{"access_token":"tok","expires_in":3600}"""
        every { credentialService.resolve(userId, authSettingId) } returns null
        every { pendingRegistry.register(any()) } returns CompletableFuture.completedFuture("code")
        every { httpClient.newCall(any()) } returnsMany listOf(mockCall(discoveryJson), mockCall(tokenJson))
        val slot = slot<Credential>()
        every { credentialService.store(capture(slot)) } answers { slot.captured }
        val result = runBlocking { service().resolveOAuthCredential(userId, discoverableSetting(), namespaceId, caseId, agentId, "agent") { it } }
        result.shouldNotBeNull()
        result.data["accessToken"] shouldBe "tok"
        verify(exactly = 2) { httpClient.newCall(any()) }
    }

    "extracts endpoints for OAUTH_REGISTERED without discovery HTTP call" {
        val tokenJson = """{"access_token":"tok","expires_in":3600}"""
        every { credentialService.resolve(userId, authSettingId) } returns null
        every { pendingRegistry.register(any()) } returns CompletableFuture.completedFuture("code")
        every { httpClient.newCall(any()) } returns mockCall(tokenJson)
        val slot = slot<Credential>()
        every { credentialService.store(capture(slot)) } answers { slot.captured }
        val result = runBlocking { service().resolveOAuthCredential(userId, registeredSetting(), namespaceId, caseId, agentId, "agent") { it } }
        result.shouldNotBeNull()
        verify(exactly = 1) { httpClient.newCall(any()) }
    }

    "PKCE code_challenge is S256 of code_verifier" {
        val pkce = service().generatePkce()
        val digest = java.security.MessageDigest.getInstance("SHA-256").digest(pkce.codeVerifier.toByteArray(Charsets.US_ASCII))
        val expected = java.util.Base64.getUrlEncoder().withoutPadding().encodeToString(digest)
        pkce.codeChallenge shouldBe expected
        pkce.codeVerifier.length shouldBe 43
    }

    "returns null when redirectUri is blank" {
        every { credentialService.resolve(userId, authSettingId) } returns null
        val result = runBlocking { service(redirectUri = "").resolveOAuthCredential(userId, registeredSetting(), namespaceId, caseId, agentId, "agent", emitEvent) }
        result.shouldBeNull()
        verify(exactly = 0) { pendingRegistry.register(any()) }
    }
})
