package io.whozoss.agentos.plugins.http

import io.kotest.core.spec.IsolationMode
import io.kotest.core.spec.style.StringSpec
import io.kotest.matchers.shouldBe
import io.kotest.matchers.string.shouldNotContain
import io.whozoss.agentos.plugins.http.auth.AuthHeaderSpec
import io.whozoss.agentos.plugins.http.config.ApiKeyPlacement
import io.whozoss.agentos.plugins.http.config.AuthConfig
import io.whozoss.agentos.plugins.http.net.HttpClientHolder
import io.whozoss.agentos.plugins.http.testing.CapturedLogs
import io.whozoss.agentos.plugins.http.testing.ExecutionFixture
import io.whozoss.agentos.plugins.http.testing.TestHttpServer
import io.whozoss.agentos.plugins.http.testing.TestResponse
import io.whozoss.agentos.sdk.credential.Credential
import io.whozoss.agentos.sdk.credential.CredentialType
import io.whozoss.agentos.sdk.tool.ToolExecutionResult
import java.util.UUID

/**
 * With known secrets in the credential, nothing the plugin produces (tool output, metadata, log lines at any
 * level, recorded request path) contains them, whatever the placement and whatever the target answers.
 */
class SecretNeverLeaksSpec : StringSpec({
    isolationMode = IsolationMode.InstancePerLeaf

    val holder = HttpClientHolder(ExecutionFixture.policy)
    val server = TestHttpServer()
    afterSpec {
        server.close()
        holder.shutdown()
    }
    val context = ExecutionFixture.context()
    val config = ExecutionFixture.config(baseUrl = "${server.baseUrl}/api")

    fun credential(type: CredentialType, data: Map<String, String>): Credential =
        Credential(userId = UUID.randomUUID(), authSettingId = UUID.randomUUID(), credentialType = type, data = data)

    val secrets = listOf("tok-SECRET-123", "qk-SECRET-456", "pw-SECRET-789", "hdr-SECRET-000")
    val specs: Map<String, AuthHeaderSpec> = mapOf(
        "bearer token" to AuthHeaderSpec.from(
            credential(CredentialType.BEARER_TOKEN, mapOf("token" to "tok-SECRET-123")),
            AuthConfig(),
            providerBound = true,
        ),
        "query API key" to AuthHeaderSpec.from(
            credential(CredentialType.API_KEY, mapOf("key" to "qk-SECRET-456")),
            AuthConfig(apiKeyIn = ApiKeyPlacement.QUERY, apiKeyName = "api_key"),
            providerBound = true,
        ),
        "basic password" to AuthHeaderSpec.from(
            credential(
                CredentialType.BASIC_AUTH,
                mapOf("username" to "bot@corp.com/token", "password" to "pw-SECRET-789"),
            ),
            AuthConfig(),
            providerBound = true,
        ),
        "header API key" to AuthHeaderSpec.from(
            credential(CredentialType.API_KEY, mapOf("key" to "hdr-SECRET-000")),
            AuthConfig(apiKeyIn = ApiKeyPlacement.HEADER, apiKeyName = "X-Api-Key"),
            providerBound = true,
        ),
    )

    fun everything(result: ToolExecutionResult): String =
        listOf(result.output, result.errorMessage.orEmpty(), result.metadata.toString(), result.toString())
            .joinToString("\n")

    fun assertNoSecret(text: String) {
        secrets.forEach { secret -> text shouldNotContain secret }
    }

    val answers = listOf(200 to """{"item":{"id":"1","name":"n"}}""", 401 to """{"error":"denied"}""", 500 to "boom")
    specs.forEach { (label, authSpec) ->
        answers.forEach { (status, body) ->
            "a $label never appears in output, metadata, logs or path on a $status answer" {
                CapturedLogs.clear()
                server.requests.clear()
                server.handler = { TestResponse(status = status, body = body) }
                val runtime = ExecutionFixture.runtime(config, holder.client, authSpec = authSpec)
                val tools = ExecutionFixture.tools(config, runtime)
                val get = checkNotNull(tools["showItem"]).executeWithJson("""{"id":"1"}""", context)
                val post = checkNotNull(tools["createItem"]).executeWithJson("""{"body":{"name":"n"}}""", context)
                server.requests.size shouldBe 2
                assertNoSecret(everything(get))
                assertNoSecret(everything(post))
                assertNoSecret(CapturedLogs.lines.joinToString("\n"))
                assertNoSecret(authSpec.toString())
                get.metadata["path"] shouldBe "/api/items/1"
                get.metadata.keys shouldBe setOf("status", "contentType", "bytes", "truncated", "path")
            }
        }
    }

    listOf(200, 404, 500).forEach { status ->
        "a secret in a $status answer body never appears in the logs, at any level" {
            CapturedLogs.clear()
            server.handler = { TestResponse(status = status, body = """{"access_token":"resp-SECRET-321"}""") }
            val tools = ExecutionFixture.tools(config, ExecutionFixture.runtime(config, holder.client))
            checkNotNull(tools["createToken"]).executeWithJson("""{"body":{"grant_type":"x"}}""", context)
            server.requests.size shouldBe 1
            CapturedLogs.lines.joinToString("\n") shouldNotContain "resp-SECRET-321"
        }
    }

    "the credential does reach the target (the secret is used, not dropped)" {
        server.handler = { TestResponse(body = "{}") }
        val bearer = checkNotNull(specs["bearer token"])
        val tools = ExecutionFixture.tools(config, ExecutionFixture.runtime(config, holder.client, authSpec = bearer))
        checkNotNull(tools["showItem"]).executeWithJson("""{"id":"1"}""", context)
        server.requests.single().header("Authorization") shouldBe "Bearer tok-SECRET-123"
    }
})
