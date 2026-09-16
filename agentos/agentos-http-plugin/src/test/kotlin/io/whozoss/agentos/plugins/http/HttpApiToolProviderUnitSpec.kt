package io.whozoss.agentos.plugins.http

import com.fasterxml.jackson.databind.JsonNode
import com.fasterxml.jackson.databind.node.NullNode
import io.kotest.core.spec.IsolationMode
import io.kotest.core.spec.style.StringSpec
import io.kotest.engine.spec.tempfile
import io.kotest.matchers.collections.shouldContainExactly
import io.kotest.matchers.nulls.shouldBeNull
import io.kotest.matchers.shouldBe
import io.kotest.matchers.string.shouldContain
import io.kotest.matchers.string.shouldNotContain
import io.kotest.matchers.string.shouldStartWith
import io.whozoss.agentos.plugins.http.cache.OperationCatalogueCache
import io.whozoss.agentos.plugins.http.net.HttpClientHolder
import io.whozoss.agentos.plugins.http.net.SpecFetcher
import io.whozoss.agentos.plugins.http.openapi.Fixtures
import io.whozoss.agentos.plugins.http.openapi.json
import io.whozoss.agentos.plugins.http.testing.CapturedLogs
import io.whozoss.agentos.plugins.http.testing.ExecutionFixture
import io.whozoss.agentos.plugins.http.testing.TestHttpServer
import io.whozoss.agentos.plugins.http.testing.TestResponse
import io.whozoss.agentos.sdk.credential.Credential
import io.whozoss.agentos.sdk.credential.CredentialType
import io.whozoss.agentos.sdk.tool.ConfirmationMode
import io.whozoss.agentos.sdk.tool.StandardTool
import io.whozoss.agentos.sdk.tool.ToolContext
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import java.time.Clock
import java.time.Duration
import java.time.Instant
import java.time.ZoneId
import java.time.ZoneOffset
import java.util.UUID
import java.util.concurrent.atomic.AtomicInteger

class HttpApiToolProviderUnitSpec : StringSpec({
    isolationMode = IsolationMode.InstancePerLeaf

    class MutableClock(var now: Instant = Instant.parse("2026-09-08T10:00:00Z")) : Clock() {
        override fun getZone(): ZoneId = ZoneOffset.UTC
        override fun withZone(zone: ZoneId): Clock = this
        override fun instant(): Instant = now
        fun advance(duration: Duration) {
            now = now.plus(duration)
        }
    }

    val policy = ExecutionFixture.policy
    val holder = HttpClientHolder(policy)
    val server = TestHttpServer()
    afterSpec {
        server.close()
        holder.shutdown()
    }
    val clock = MutableClock()
    val failures = LastFailureRegistry()
    val cache = OperationCatalogueCache(fetcher = SpecFetcher(holder.client, policy), clock = clock)
    val services = HttpApiPluginServices(
        client = holder.client,
        catalogueCache = cache,
        failures = failures,
        limiters = CallLimiters(),
        urlPolicy = policy,
    )
    val provider = HttpApiToolProvider { services }
    val petstore = Fixtures.load("petstore-3.0.yaml")
    val namespace = UUID.randomUUID()

    /** Serves the petstore document with an etag; answers 304 to a matching If-None-Match. */
    fun servePetstore(etag: String = "\"v1\"") {
        server.handler = { request ->
            if (request.header("If-None-Match") == etag) {
                TestResponse(status = 304)
            } else {
                TestResponse(body = petstore, contentType = "application/yaml", headers = mapOf("ETag" to etag))
            }
        }
    }

    fun specConfig(vararg extra: String): JsonNode {
        val fields = listOf(
            """"spec": {"url": "${server.baseUrl}/openapi.yaml", "refreshMinutes": 60}""",
            """"baseUrl": "${server.baseUrl}/v1"""",
        ) + extra
        return json("{${fields.joinToString(",")}}")
    }

    fun context(namespaceId: UUID = namespace, credentialProvider: (() -> Credential?)? = null): ToolContext =
        ToolContext(
            namespaceId = namespaceId,
            userId = null,
            userExternalId = null,
            caseEvents = emptyList(),
            credentialProvider = credentialProvider,
        )

    fun specRequests(): Int = server.requests.count { it.path == "/openapi.yaml" }

    "declares the HTTP_API integration type with a schema" {
        provider.integrationType shouldBe "HTTP_API"
        provider.configSchema shouldBe HttpApiConfigSchema.schema
    }

    "a null or JSON-null config yields no tools" {
        provider.provideTools(null, "PETS", context()) shouldBe emptyList()
        provider.provideTools(NullNode.instance, "PETS", context()) shouldBe emptyList()
        specRequests() shouldBe 0
    }

    "an invalid config yields no tools, records the failure and describes it" {
        val invalid = json("""{"spec": {"inline": "openapi: 3.0.0"}}""")
        provider.provideTools(invalid, "PETS", context()) shouldBe emptyList()
        failures.get(namespace, "PETS") shouldContain "'baseUrl' is required"
        provider.describeNamespace(invalid, "PETS", context()) shouldBe
            "Integration PETS (HTTP_API): not available " +
            "('baseUrl' is required because the OpenAPI document declares no absolute server URL): no tools exposed"
    }

    "a recorded failure is scoped to the namespace of the run" {
        val invalid = json("""{"spec": {"inline": "openapi: 3.0.0"}}""")
        val otherNamespace = UUID.randomUUID()
        provider.provideTools(invalid, "PETS", context()) shouldBe emptyList()
        provider.describeNamespace(invalid, "PETS", context(namespaceId = otherNamespace)).shouldBeNull()
        servePetstore()
        provider.provideTools(specConfig(), "PETS", context(namespaceId = otherNamespace)).size shouldBe 3
        failures.get(namespace, "PETS") shouldContain "'baseUrl' is required"
        provider.describeNamespace(invalid, "PETS", context()) shouldContain "'baseUrl' is required"
    }

    "an integration name too long for tool names is a recorded configuration error" {
        val name = "N".repeat(57)
        servePetstore()
        provider.provideTools(specConfig(), name, context()) shouldBe emptyList()
        failures.get(namespace, name) shouldContain "too long"
        specRequests() shouldBe 0
    }

    listOf("Zendesk Prod", "A__B", "pets.v2").forEach { name ->
        "an integration name that cannot prefix a tool name is a recorded configuration error: '$name'" {
            servePetstore()
            CapturedLogs.clear()
            provider.provideTools(specConfig(), name, context()) shouldBe emptyList()
            failures.get(namespace, name) shouldContain "integration name must"
            CapturedLogs.lines.count { "ERROR" in it && "integration name must" in it } shouldBe 1
            provider.describeNamespace(specConfig(), name, context()) shouldStartWith
                "Integration $name (HTTP_API): not available (integration name must"
            specRequests() shouldBe 0
        }
    }

    "fetches the document once for two runs with the same config and names the tools after the config" {
        servePetstore()
        val first = provider.provideTools(specConfig(), "PETS", context())
        val second = provider.provideTools(specConfig(), "PETS", context())
        first.map { it.name } shouldContainExactly listOf("PETS__listPets", "PETS__showPetById", "PETS__listStores")
        second.map { it.name } shouldContainExactly first.map { it.name }
        first.map { it.name.substringBefore("__") }.toSet() shouldBe setOf("PETS")
        first.map { it.name }.toSet().size shouldBe first.size
        specRequests() shouldBe 1
        failures.get(namespace, "PETS").shouldBeNull()
    }

    "refetches after the refresh interval and keeps the catalogue on a 304" {
        servePetstore()
        provider.provideTools(specConfig(), "PETS", context()).size shouldBe 3
        clock.advance(Duration.ofMinutes(61))
        provider.provideTools(specConfig(), "PETS", context()).size shouldBe 3
        specRequests() shouldBe 2
        server.requests.last().header("If-None-Match") shouldBe "\"v1\""
    }

    "a refresh failure serves the cached catalogue, records the failure and describes both" {
        servePetstore()
        provider.provideTools(specConfig(), "PETS", context()).size shouldBe 3
        clock.advance(Duration.ofMinutes(61))
        server.handler = { TestResponse(status = 503, body = "down") }
        provider.provideTools(specConfig(), "PETS", context()).size shouldBe 3
        failures.get(namespace, "PETS") shouldContain "HTTP 503"
        provider.describeNamespace(specConfig(), "PETS", context()) shouldBe
            "Integration PETS (HTTP_API): Petstore 1.0.0 — 3 operations exposed (read-only) " +
            "(document refresh failing: HTTP 503 fetching the OpenAPI document)"
    }

    "a document failure without cache yields no tools and a describing line" {
        server.handler = { TestResponse(status = 404, body = "nope") }
        provider.provideTools(specConfig(), "PETS", context()) shouldBe emptyList()
        provider.describeNamespace(specConfig(), "PETS", context()) shouldBe
            "Integration PETS (HTTP_API): not available " +
            "(HTTP 404 fetching the OpenAPI document): no tools exposed"
    }

    "too many operations yields no tools and names the limit" {
        servePetstore()
        provider.provideTools(specConfig(""""maxTools": 2"""), "PETS", context()) shouldBe emptyList()
        failures.get(namespace, "PETS") shouldBe "3 operations selected, exceeds maxTools=2"
    }

    "resolves the credential exactly once per run" {
        servePetstore()
        val calls = AtomicInteger()
        val credential = Credential(
            userId = UUID.randomUUID(),
            authSettingId = UUID.randomUUID(),
            credentialType = CredentialType.BEARER_TOKEN,
            data = mapOf("token" to "t"),
        )
        val tools = provider.provideTools(specConfig(), "PETS", context { calls.incrementAndGet(); credential })
        tools.size shouldBe 3
        calls.get() shouldBe 1
    }

    "a failing credential provider never escapes and its message stays in the logs" {
        servePetstore()
        CapturedLogs.clear()
        val failing = context { throw IllegalStateException("vault down") }
        provider.provideTools(specConfig(), "PETS", failing) shouldBe emptyList()
        failures.get(namespace, "PETS") shouldBe "unexpected error while preparing the tools, see the service logs"
        CapturedLogs.lines.joinToString("\n") shouldContain "vault down"
    }

    "a customised API key placement without a credential provider is logged as unauthenticated" {
        servePetstore()
        CapturedLogs.clear()
        provider.provideTools(specConfig(""""auth": {"apiKeyIn": "query"}"""), "PETS", context()).size shouldBe 3
        CapturedLogs.lines.count { "WARN" in it && "calls are sent unauthenticated" in it } shouldBe 1
        CapturedLogs.clear()
        provider.provideTools(specConfig(), "PETS", context()).size shouldBe 3
        CapturedLogs.lines.none { "calls are sent unauthenticated" in it } shouldBe true
    }

    "a plugin that is not started yields no tools and no description" {
        val stopped = HttpApiToolProvider { throw IllegalStateException("HTTP API plugin is not started") }
        stopped.provideTools(specConfig(), "PETS", context()) shouldBe emptyList()
        stopped.describeNamespace(specConfig(), "PETS", context()).shouldBeNull()
    }

    "describeNamespace performs no request on a cold cache and returns null" {
        servePetstore()
        provider.describeNamespace(specConfig(), "PETS", context()).shouldBeNull()
        specRequests() shouldBe 0
    }

    "describeNamespace describes a loaded catalogue without credentials" {
        servePetstore()
        provider.provideTools(specConfig(), "PETS", context())
        provider.describeNamespace(specConfig(), "PETS", context()) shouldBe
            "Integration PETS (HTTP_API): Petstore 1.0.0 — 3 operations exposed (read-only)"
        provider.provideTools(specConfig(""""allowMutations": true"""), "PETS", context())
        provider.describeNamespace(specConfig(""""allowMutations": true"""), "PETS", context()) shouldBe
            "Integration PETS (HTTP_API): Petstore 1.0.0 — 6 operations exposed (read/write)"
        provider.describeNamespace(null, "PETS", context()).shouldBeNull()
    }

    "allowMutations exposes [WRITE] tools requiring confirmation next to GET tools that do not" {
        servePetstore()
        val readOnly = provider.provideTools(specConfig(), "PETS", context())
        readOnly.none { it.description.startsWith("[WRITE]") } shouldBe true
        val writableConfig = specConfig(""""allowMutations": true""")
        val writable: List<StandardTool<*>> = provider.provideTools(writableConfig, "PETS", context())
        val writes = writable.filter { it.description.startsWith("[WRITE]") }
        writes.map { it.name } shouldContainExactly listOf("PETS__createPet", "PETS__deletePet", "PETS__updatePet")
        writes.forEach { it.getConfirmationMode() shouldBe ConfirmationMode.EVERY_TIME }
        writable.filterNot { it in writes }.forEach { it.getConfirmationMode() shouldBe ConfirmationMode.NONE }
        writable.first { it.name == "PETS__createPet" }.description shouldStartWith "[WRITE] POST /pets"
    }

    "two runs of the same config share the concurrency limit" {
        val inFlight = AtomicInteger()
        val maxInFlight = AtomicInteger()
        server.handler = { request ->
            if (request.path == "/openapi.yaml") {
                TestResponse(body = petstore, contentType = "application/yaml")
            } else {
                maxInFlight.accumulateAndGet(inFlight.incrementAndGet(), ::maxOf)
                Thread.sleep(300)
                inFlight.decrementAndGet()
                TestResponse(body = "[]")
            }
        }
        val config = specConfig(""""maxConcurrentCalls": 1""")
        val firstRun = provider.provideTools(config, "PETS", context()).first { it.name == "PETS__listPets" }
        val secondRun = provider.provideTools(config, "PETS", context()).first { it.name == "PETS__listPets" }
        val results = coroutineScope {
            listOf(firstRun, secondRun).map { async { it.executeWithJson("{}", context()) } }.awaitAll()
        }
        results.all { it.success } shouldBe true
        maxInFlight.get() shouldBe 1
    }

    "exposes the tools of a document read from a file, without any fetch" {
        val file = tempfile(suffix = ".yaml")
        file.writeText(petstore)
        val config = json("""{"spec": {"file": "${file.absolutePath}"}, "baseUrl": "${server.baseUrl}/v1"}""")
        provider.provideTools(config, "PETS", context()).map { it.name } shouldContainExactly
            listOf("PETS__listPets", "PETS__showPetById", "PETS__listStores")
        specRequests() shouldBe 0
        provider.describeNamespace(config, "PETS", context()) shouldBe
            "Integration PETS (HTTP_API): Petstore 1.0.0 — 3 operations exposed (read-only)"
    }

    "a file document that fails to load is described generically, the detail staying in the logs" {
        val sentinel = "SUPER-SECRET-do-not-leak"
        val file = tempfile(suffix = ".yaml")
        file.writeText("openapi: 3.0.0\ninfo: { title: t, version: \"1\" }\npassword: [$sentinel\n")
        val config = json("""{"spec": {"file": "${file.absolutePath}"}, "baseUrl": "${server.baseUrl}/v1"}""")
        CapturedLogs.clear()
        provider.provideTools(config, "PETS", context()) shouldBe emptyList()
        failures.get(namespace, "PETS") shouldBe "document file cannot be loaded, see the service logs"
        provider.describeNamespace(config, "PETS", context()) shouldBe
            "Integration PETS (HTTP_API): not available (document file cannot be loaded, see the service logs): " +
            "no tools exposed"
        val logged = CapturedLogs.lines.joinToString("\n")
        logged shouldContain "OpenAPI document cannot be parsed"
        logged shouldNotContain sentinel
        val missingPath = "${file.absolutePath}.missing.yaml"
        val missing = json("""{"spec": {"file": "$missingPath"}, "baseUrl": "${server.baseUrl}/v1"}""")
        provider.provideTools(missing, "PETS", context()) shouldBe emptyList()
        failures.get(namespace, "PETS") shouldBe "document file cannot be loaded, see the service logs"
    }

    "sends the API key where the document security scheme says, unless the config auth is explicit" {
        val keyed = Fixtures.load("api-key-scheme.yaml")
        server.handler = { request ->
            if (request.path == "/openapi.yaml") {
                TestResponse(body = keyed, contentType = "application/yaml")
            } else {
                TestResponse(body = "[]")
            }
        }
        val credential = Credential(
            userId = UUID.randomUUID(),
            authSettingId = UUID.randomUUID(),
            credentialType = CredentialType.API_KEY,
            data = mapOf("key" to "k3y"),
        )
        CapturedLogs.clear()
        val fromDocument = provider.provideTools(specConfig(), "KEYED", context { credential }).single()
        fromDocument.executeWithJson("{}", context()).success shouldBe true
        server.requests.last().query shouldBe "api_key=k3y"
        CapturedLogs.lines.count { "DEBUG" in it && "API key placement" in it && "api_key" in it } shouldBe 1
        provider.describeNamespace(specConfig(), "KEYED", context()) shouldBe
            "Integration KEYED (HTTP_API): Keyed 1.0.0 — 1 operations exposed (read-only, api key)"
        val explicit = specConfig(""""auth": {"apiKeyIn": "header", "apiKeyName": "X-Token"}""")
        provider.provideTools(explicit, "KEYED", context { credential }).single().executeWithJson("{}", context())
        server.requests.last().query.shouldBeNull()
        server.requests.last().header("X-Token") shouldBe "k3y"
        provider.describeNamespace(explicit, "KEYED", context()) shouldBe
            "Integration KEYED (HTTP_API): Keyed 1.0.0 — 1 operations exposed (read-only, api key)"
    }

    "describes a default placement without any api key mention" {
        servePetstore()
        provider.provideTools(specConfig(), "PETS", context()).size shouldBe 3
        provider.describeNamespace(specConfig(), "PETS", context()) shouldBe
            "Integration PETS (HTTP_API): Petstore 1.0.0 — 3 operations exposed (read-only)"
        val bearer = specConfig(""""auth": {"apiKeyIn": "bearer"}""")
        provider.provideTools(bearer, "PETS", context()).size shouldBe 3
        provider.describeNamespace(bearer, "PETS", context()) shouldBe
            "Integration PETS (HTTP_API): Petstore 1.0.0 — 3 operations exposed (read-only, api key)"
    }

    "a configName is required" {
        servePetstore()
        provider.provideTools(specConfig(), null, context()) shouldBe emptyList()
        specRequests() shouldBe 0
    }
})
