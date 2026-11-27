package io.whozoss.agentos.plugins.http

import io.kotest.core.spec.style.StringSpec
import io.kotest.matchers.shouldBe
import io.whozoss.agentos.plugins.http.testing.ExecutionFixture
import io.whozoss.agentos.sdk.auth.ConnectionCapability
import io.whozoss.agentos.sdk.credential.Credential
import kotlinx.coroutines.async
import okhttp3.OkHttpClient
import okhttp3.ResponseBody.Companion.toResponseBody

class ConnectionCapabilityUnitSpec : StringSpec({
    for ((type, material) in listOf(
        io.whozoss.agentos.sdk.credential.CredentialType.OAUTH_TOKENS to mapOf("accessToken" to "synthetic", "scope" to "read"),
        io.whozoss.agentos.sdk.credential.CredentialType.BEARER_TOKEN to mapOf("token" to "synthetic"),
        io.whozoss.agentos.sdk.credential.CredentialType.BASIC_AUTH to mapOf("username" to "synthetic-user", "password" to "synthetic-password"),
        io.whozoss.agentos.sdk.credential.CredentialType.API_KEY to mapOf("key" to "synthetic"),
    )) {
    for (removed in material.keys.filter { it != "scope" }) {
    for (replacement in listOf<String?>(null, " ", "revoked", "fresh")) {
    "final $type $removed ${replacement ?: "absent"} after a real permit wait is validated" {
        val config = ExecutionFixture.config(baseUrl = "https://api.example.test/api")
        val initial = Credential(userId = java.util.UUID.randomUUID(), authSettingId = java.util.UUID.randomUUID(),
            credentialType = type, data = material)
        var outbound = 0
        val client = OkHttpClient.Builder().addInterceptor { chain ->
            outbound++
            val fresh = initial.copy(data = material + (removed to "fresh"))
            val auth = io.whozoss.agentos.plugins.http.auth.AuthHeaderSpec.from(fresh,
                io.whozoss.agentos.plugins.http.config.AuthConfig(), providerBound = true)
            when (auth) {
                is io.whozoss.agentos.plugins.http.auth.AuthHeaderSpec.Header -> chain.request().header(auth.name) shouldBe auth.value
                is io.whozoss.agentos.plugins.http.auth.AuthHeaderSpec.Query -> chain.request().url.queryParameter(auth.name) shouldBe auth.value
                else -> error("Expected usable fresh authentication")
            }
            okhttp3.Response.Builder().request(chain.request()).protocol(okhttp3.Protocol.HTTP_1_1).code(200).message("OK")
                .body("""{"items":[]}""".toResponseBody()).build()
        }.build()
        val current = java.util.concurrent.atomic.AtomicReference<Credential?>(initial)
        val authorized = kotlinx.coroutines.CompletableDeferred<Unit>()
        val capability = object : ConnectionCapability {
            override fun requireActive() = Unit
            override fun authorize(destination: String): Credential? { val credential = current.get(); authorized.complete(Unit); return credential }
        }
        val r = ExecutionFixture.runtime(config, client)
        val semaphore = kotlinx.coroutines.sync.Semaphore(1, acquiredPermits = 1)
        val waiting = kotlinx.coroutines.CompletableDeferred<Unit>()
        val limiter = object : kotlinx.coroutines.sync.Semaphore by semaphore {
            override suspend fun acquire() { waiting.complete(Unit); semaphore.acquire() }
        }
        val runtime = HttpApiRuntime(r.configName, config.baseUrl!!, r.auth, r.defaultHeaders, r.timeoutSeconds,
            limiter, r.urlPolicy, client, capability, io.whozoss.agentos.plugins.http.config.AuthConfig())
        try {
            kotlinx.coroutines.coroutineScope {
                val call = async { ExecutionFixture.tools(config, runtime).getValue("listItems").executeWithJson("{}", ExecutionFixture.context()) }
                authorized.await()
                waiting.await()
                current.set(if (replacement == "revoked") null else initial.copy(
                    data = if (replacement == null) material - removed else material + (removed to replacement)))
                semaphore.release()
                val result = call.await()
                result.errorType shouldBe if (replacement == "fresh") null else "AUTH_MISSING"
                result.success shouldBe (replacement == "fresh")
                outbound shouldBe if (replacement == "fresh") 1 else 0
                semaphore.availablePermits shouldBe 1
            }
        } finally { client.dispatcher.executorService.shutdown(); client.connectionPool.evictAll() }
    }
    }
    }
    }
    "host resource denial happens before cached auth supplier or outbound" {
        val config = ExecutionFixture.config(baseUrl = "https://api.example.test/api")
        var outbound = 0
        val client = OkHttpClient.Builder().addInterceptor { outbound++; error("Unexpected network") }.build()
        val capability = object : ConnectionCapability {
            override fun requireActive() = Unit
            override fun authorize(destination: String): Credential = error("CONNECTION_POLICY_DENIED")
        }
        val runtime = ExecutionFixture.runtime(config, client).let { r -> HttpApiRuntime(r.configName, config.baseUrl!!, r.auth,
            r.defaultHeaders, r.timeoutSeconds, r.semaphore, r.urlPolicy, client, capability) }
        val tool = ExecutionFixture.tools(config, runtime).getValue("listItems")
        try {
            val result = tool.executeWithJson("{}", ExecutionFixture.context())
            result.errorType shouldBe "CONNECTION_POLICY_DENIED"
            outbound shouldBe 0
        } finally { client.dispatcher.executorService.shutdown(); client.connectionPool.evictAll() }
    }
    "governed resource policy is rechecked after waiting for the transport permit" {
        val config = ExecutionFixture.config(baseUrl = "https://api.example.test/api")
        var outbound = 0
        var active = true
        val client = OkHttpClient.Builder().addInterceptor { outbound++; error("Unexpected network") }.build()
        val capability = object : ConnectionCapability {
            override fun requireActive() { check(active) { "CONNECTION_POLICY_DENIED" } }
            override fun authorize(destination: String): Credential { requireActive(); return Credential(userId = java.util.UUID.randomUUID(), authSettingId = java.util.UUID.randomUUID(), credentialType = io.whozoss.agentos.sdk.credential.CredentialType.BEARER_TOKEN, data = mapOf("token" to "synthetic")) }
        }
        val r = ExecutionFixture.runtime(config, client)
        val limiter = object : kotlinx.coroutines.sync.Semaphore by r.semaphore {
            override suspend fun acquire() { active = false; r.semaphore.acquire() }
        }
        val runtime = HttpApiRuntime(r.configName, config.baseUrl!!, r.auth, r.defaultHeaders, r.timeoutSeconds,
            limiter, r.urlPolicy, client, capability, io.whozoss.agentos.plugins.http.config.AuthConfig())
        try {
            ExecutionFixture.tools(config, runtime).getValue("listItems").executeWithJson("{}", ExecutionFixture.context()).errorType shouldBe "CONNECTION_POLICY_DENIED"
            outbound shouldBe 0
        } finally { client.dispatcher.executorService.shutdown(); client.connectionPool.evictAll() }
    }
    "allowed host capability binds the final URL and token without invoking cached auth" {
        val config = ExecutionFixture.config(baseUrl = "https://api.example.test/api")
        var outbound = 0
        val seen = mutableListOf<String>()
        val client = OkHttpClient.Builder().addInterceptor { chain ->
            outbound++
            chain.request().url.toString() shouldBe "https://api.example.test/api/items"
            chain.request().header("Authorization") shouldBe "Bearer synthetic-bound"
            okhttp3.Response.Builder().request(chain.request()).protocol(okhttp3.Protocol.HTTP_1_1).code(200).message("OK")
                .body("""{"items":[]}""".toResponseBody()).build()
        }.build()
        val capability = object : ConnectionCapability {
            override fun requireActive() = Unit
            override fun authorize(destination: String): Credential {
                seen += destination
                return Credential(userId = java.util.UUID.randomUUID(), authSettingId = java.util.UUID.randomUUID(), credentialType = io.whozoss.agentos.sdk.credential.CredentialType.BEARER_TOKEN, data = mapOf("token" to "synthetic-bound"))
            }
        }
        val r = ExecutionFixture.runtime(config, client)
        val runtime = HttpApiRuntime(r.configName, config.baseUrl!!, io.whozoss.agentos.plugins.http.auth.RunAuth("ITEMS") { error("Cached supplier must not run") }, r.defaultHeaders, r.timeoutSeconds,
            r.semaphore, r.urlPolicy, client, capability, io.whozoss.agentos.plugins.http.config.AuthConfig())
        try {
            ExecutionFixture.tools(config, runtime).getValue("listItems").executeWithJson("{}", ExecutionFixture.context()).success shouldBe true
            seen shouldBe listOf("https://api.example.test/api/items", "https://api.example.test/api/items")
            outbound shouldBe 1
        } finally { client.dispatcher.executorService.shutdown(); client.connectionPool.evictAll() }
    }
})
