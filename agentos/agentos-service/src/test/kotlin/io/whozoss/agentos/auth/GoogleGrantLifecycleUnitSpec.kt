package io.whozoss.agentos.auth

import com.fasterxml.jackson.databind.ObjectMapper
import io.kotest.assertions.throwables.shouldThrow
import io.kotest.core.spec.style.StringSpec
import io.kotest.matchers.nulls.shouldNotBeNull
import io.kotest.matchers.shouldBe
import io.mockk.every
import io.mockk.mockk
import io.mockk.verify
import io.whozoss.agentos.authSetting.OAuthRegisteredAuthSetting
import io.whozoss.agentos.credential.CredentialService
import io.whozoss.agentos.sdk.credential.Credential
import io.whozoss.agentos.sdk.credential.CredentialType
import okhttp3.OkHttpClient
import okhttp3.Protocol
import okhttp3.Response
import okhttp3.ResponseBody.Companion.toResponseBody
import java.time.Instant
import java.util.UUID

class GoogleGrantLifecycleUnitSpec : StringSpec({
    val readonly = "https://www.googleapis.com/auth/drive.readonly"
    "governed saved read-only grant returns without refresh" {
        val user = UUID.randomUUID()
        val setting = OAuthRegisteredAuthSetting(name = "GOOGLE", clientId = "synthetic-client",
            authorizationUrl = "https://accounts.google.com/o/oauth2/v2/auth", tokenUrl = "https://oauth2.googleapis.com/token", scopes = readonly)
        val saved = Credential(userId = user, authSettingId = setting.metadata.id, credentialType = CredentialType.OAUTH_TOKENS,
            data = mapOf("accessToken" to "synthetic-token", "scope" to readonly, "expiresAt" to Instant.now().plusSeconds(3600).toString()))
        val creds = mockk<CredentialService>()
        every { creds.resolve(user, setting.metadata.id) } returns saved
        val client = OkHttpClient.Builder().addInterceptor { error("Unexpected outbound request") }.build()
        val flow = OAuthFlowService(mockk(), creds, client, ObjectMapper(), OAuthConfigProperties(redirectUri = ""))
        try {
            flow.resolveGovernedGoogleCredential(user, setting, UUID.randomUUID(), UUID.randomUUID(), UUID.randomUUID(), "agent", { it }) shouldBe saved
            verify(exactly = 0) { creds.store(any()) }
        } finally { flow.shutdown(); client.dispatcher.executorService.shutdown(); client.connectionPool.evictAll() }
    }
    listOf("omitted" to "", "broad" to ",\"scope\":\"https://www.googleapis.com/auth/drive\"").forEach { (kind, scopeJson) ->
        "governed new authorization rejects $kind actual scope before storage" {
            val user = UUID.randomUUID()
            val setting = OAuthRegisteredAuthSetting(name = "GOOGLE", clientId = "synthetic-client",
                authorizationUrl = "https://accounts.google.com/o/oauth2/v2/auth",
                tokenUrl = "https://oauth2.googleapis.com/token", scopes = readonly)
            val creds = mockk<CredentialService>()
            every { creds.resolve(user, setting.metadata.id) } returns null
            every { creds.store(any()) } answers { firstArg() }
            val registry = mockk<OAuthPendingRegistry>()
            every { registry.register(any(), user) } returns java.util.concurrent.CompletableFuture.completedFuture("synthetic-code")
            var requests = 0
            val client = OkHttpClient.Builder().addInterceptor { chain ->
                requests++
                chain.request().url.toString() shouldBe setting.tokenUrl
                Response.Builder().request(chain.request()).protocol(Protocol.HTTP_1_1).code(200).message("OK")
                    .body("""{"access_token":"synthetic-new","expires_in":3600$scopeJson}""".toResponseBody()).build()
            }.build()
            val flow = OAuthFlowService(registry, creds, client, ObjectMapper(),
                OAuthConfigProperties(redirectUri = "https://app.example.test/callback"))
            try {
                shouldThrow<IllegalStateException> {
                    flow.resolveGovernedGoogleCredential(user, setting, UUID.randomUUID(), UUID.randomUUID(), UUID.randomUUID(), "agent", { it })
                }
                verify(exactly = 0) { creds.store(any()) }
                requests shouldBe 1
            } finally { flow.shutdown(); client.dispatcher.executorService.shutdown(); client.connectionPool.evictAll() }
        }
    }
    listOf(
        "omitted" to "",
        "readonly" to ",\"scope\":\"$readonly\"",
        "broad" to ",\"scope\":\"https://www.googleapis.com/auth/drive\"",
        "unknown" to ",\"scope\":\"unknown\"",
        "blank" to ",\"scope\":\"\"",
        "null" to ",\"scope\":null",
    ).forEach { (kind, scopeJson) ->
        "governed refresh $kind scope validates actual grant before storing or returning" {
            val user = UUID.randomUUID()
            val setting = OAuthRegisteredAuthSetting(name = "GOOGLE", clientId = "synthetic-client",
                authorizationUrl = "https://accounts.google.com/o/oauth2/v2/auth",
                tokenUrl = "https://oauth2.googleapis.com/token", scopes = readonly)
            val saved = Credential(userId = user, authSettingId = setting.metadata.id,
                credentialType = CredentialType.OAUTH_TOKENS, data = mapOf("accessToken" to "synthetic-old",
                    "refreshToken" to "synthetic-refresh", "scope" to readonly,
                    "expiresAt" to Instant.now().minusSeconds(3600).toString()))
            val creds = mockk<CredentialService>()
            every { creds.resolve(user, setting.metadata.id) } returns saved
            every { creds.store(any()) } answers { firstArg() }
            var requests = 0
            val client = OkHttpClient.Builder().addInterceptor { chain ->
                requests++
                chain.request().url.toString() shouldBe setting.tokenUrl
                val form = chain.request().body as okhttp3.FormBody
                val names = (0 until form.size).map { form.name(it) }
                form.value(names.indexOf("refresh_token")) shouldBe "synthetic-refresh"
                ("scope" in names) shouldBe false
                Response.Builder().request(chain.request()).protocol(Protocol.HTTP_1_1).code(200).message("OK")
                    .body("""{"access_token":"synthetic-new","expires_in":3600$scopeJson}""".toResponseBody()).build()
            }.build()
            val flow = OAuthFlowService(mockk(), creds, client, ObjectMapper(), OAuthConfigProperties(redirectUri = ""))
            suspend fun resolve() = flow.resolveGovernedGoogleCredential(user, setting, UUID.randomUUID(),
                UUID.randomUUID(), UUID.randomUUID(), "agent", { it })
            try {
                if (kind in setOf("omitted", "readonly")) {
                    val result = resolve().shouldNotBeNull()
                    result.data["scope"] shouldBe readonly
                    result.data["accessToken"] shouldBe "synthetic-new"
                    result.data["refreshToken"] shouldBe "synthetic-refresh"
                    verify(exactly = 1) { creds.store(any()) }
                } else {
                    shouldThrow<IllegalStateException> { resolve() }
                    verify(exactly = 0) { creds.store(any()) }
                }
                requests shouldBe 1
            } finally { flow.shutdown(); client.dispatcher.executorService.shutdown(); client.connectionPool.evictAll() }
        }
    }
})
