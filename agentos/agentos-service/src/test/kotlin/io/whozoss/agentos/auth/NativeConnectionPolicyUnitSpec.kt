package io.whozoss.agentos.auth

import com.fasterxml.jackson.databind.ObjectMapper
import io.kotest.core.spec.style.StringSpec
import io.kotest.matchers.shouldBe
import io.mockk.every
import io.mockk.mockk
import io.mockk.verify
import io.whozoss.agentos.authSetting.*
import io.whozoss.agentos.credential.CredentialService
import io.whozoss.agentos.permissions.PermissionService
import io.whozoss.agentos.permissions.ScopedAccessPolicy
import io.whozoss.agentos.sdk.credential.Credential
import io.whozoss.agentos.sdk.entity.EntityMetadata
import io.whozoss.agentos.user.User
import io.whozoss.agentos.user.UserService
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.joinAll
import kotlinx.coroutines.withTimeout
import okhttp3.OkHttpClient
import okhttp3.Protocol
import okhttp3.Response
import okhttp3.ResponseBody.Companion.toResponseBody
import java.util.UUID
import kotlin.time.Duration.Companion.milliseconds

class NativeConnectionPolicyUnitSpec : StringSpec({
    for (scenario in listOf("valid overlay", "scope changed during consent", "binding changed during consent", "setting changed during exchange", "raw row")) {
    "native connect preserves resolution context: $scenario" {
        val user = UUID.randomUUID()
        val namespace = UUID.randomUUID()
        val base = OAuthRegisteredAuthSetting(name = "READ", clientId = "synthetic", namespaceId = null,
            authorizationUrl = "https://auth.example.test/auth", tokenUrl = "https://auth.example.test/token", scopes = "read metadata")
        val registry = ConnectionPolicyRegistry(ConnectionPolicyProperties(policies = mapOf("read" to ConnectionPolicy(
            allowedAuthTypes = setOf(AuthType.OAUTH_REGISTERED), authorizationUrls = setOf(base.authorizationUrl),
            tokenUrls = setOf(base.tokenUrl), allowedScopes = setOf("read", "metadata"))),
            authSettingIds = mapOf(base.metadata.id to setOf("read"))))
        val overlay = base.copy(metadata = EntityMetadata(), namespaceId = namespace, userId = user, scopes = "read")
        val effective = AuthSettingMergeStrategy(registry).merge(base, overlay)
        effective.metadata.id shouldBe base.metadata.id
        effective.namespaceId shouldBe null
        val settings = mockk<AuthSettingService>()
        every { settings.findById(base.metadata.id) } returns base
        var current: AuthSetting = effective
        every { settings.resolveAuthSetting(namespace, user, base.name) } answers { current }
        val credentials = mockk<CredentialService>()
        every { credentials.resolve(user, base.metadata.id) } returns null
        val stored = CompletableDeferred<Credential>()
        every { credentials.store(any()) } answers { firstArg<Credential>().also { stored.complete(it) } }
        var requests = 0
        val client = OkHttpClient.Builder().addInterceptor { chain ->
            requests++
            if (scenario == "setting changed during exchange") current = base.copy(scopes = "metadata")
            chain.request().url.toString() shouldBe base.tokenUrl
            Response.Builder().request(chain.request()).protocol(Protocol.HTTP_1_1).code(200).message("OK")
                .body("""{"access_token":"synthetic","scope":"read"}""".toResponseBody()).build()
        }.build()
        val pending = OAuthPendingRegistry()
        val flow = OAuthFlowService(pending, credentials, client, ObjectMapper(),
            OAuthConfigProperties(redirectUri = "https://app.example.test/callback"), registry, settings)
        val users = mockk<UserService>()
        every { users.getCurrentUser() } returns User(metadata = EntityMetadata(id = user), externalId = "synthetic", email = "user@example.test")
        val permissions = mockk<PermissionService>(relaxed = true)
        every { permissions.hasPermission(any(), any(), any(), any()) } returns true
        val controller = AuthSettingController(settings, mockk(relaxed = true), users, permissions,
            ScopedAccessPolicy(permissions), credentials, flow)
        try {
            val url = controller.connect(base.metadata.id, if (scenario == "raw row") null else namespace).authorizationUrl
            val parsed = okhttp3.HttpUrl.run { url.toHttpUrl() }
            parsed.queryParameter("scope") shouldBe if (scenario == "raw row") "read metadata" else "read"
            if (scenario == "scope changed during consent") current = base.copy(scopes = "metadata")
            if (scenario == "binding changed during consent") current = base.copy(metadata = EntityMetadata(), scopes = "read")
            pending.resolve(parsed.queryParameter("state")!!, "synthetic-code", user) shouldBe true
            // Join the private background scope so negative assertions cannot race unfinished validation.
            val field = OAuthFlowService::class.java.getDeclaredField("connectScope").apply { isAccessible = true }
            val scope = field.get(flow) as kotlinx.coroutines.CoroutineScope
            withTimeout(3000.milliseconds) { scope.coroutineContext[kotlinx.coroutines.Job]!!.children.toList().joinAll() }
            val valid = scenario == "valid overlay" || scenario == "raw row"
            stored.isCompleted shouldBe valid
            if (valid) {
                val credential = stored.await()
                credential.authSettingId shouldBe base.metadata.id
                credential.userId shouldBe user
                credential.data["scope"] shouldBe "read"
            }
            requests shouldBe if (valid || scenario == "setting changed during exchange") 1 else 0
            verify(exactly = if (valid) 1 else 0) { credentials.store(any()) }
            if (scenario == "raw row") {
                verify(exactly = 0) { settings.resolveAuthSetting(any(), any(), any()) }
                verify(atLeast = 4) { settings.findById(base.metadata.id) }
            } else {
                verify(atLeast = 3) { settings.resolveAuthSetting(namespace, user, base.name) }
                verify(exactly = 1) { settings.findById(base.metadata.id) }
            }
        } finally { flow.shutdown(); client.dispatcher.executorService.shutdown(); client.connectionPool.evictAll() }
    }
    }
})
