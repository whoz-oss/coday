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
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.launch
import kotlinx.coroutines.runInterruptible
import java.time.Clock
import java.time.Duration
import java.time.Instant
import java.time.ZoneId
import java.time.ZoneOffset
import java.util.UUID
import java.util.concurrent.CancellationException
import java.util.concurrent.CountDownLatch
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

    /** Serves the petstore document and answers every API call with an empty JSON list. */
    fun servePetstoreApi() {
        server.handler = { request ->
            if (request.path == "/openapi.yaml") {
                TestResponse(body = petstore, contentType = "application/yaml")
            } else {
                TestResponse(body = "[]")
            }
        }
    }

    fun bearer(token: String) = Credential(
        userId = UUID.randomUUID(),
        authSettingId = UUID.randomUUID(),
        credentialType = CredentialType.BEARER_TOKEN,
        data = mapOf("token" to token),
    )

    fun List<StandardTool<*>>.named(suffix: String): StandardTool<*> = single { it.name.endsWith(suffix) }

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

    "resolves the credential on the first call of a tool, then once for the whole run" {
        servePetstoreApi()
        val calls = AtomicInteger()
        val tools = provider.provideTools(
            specConfig(),
            "PETS",
            context {
                calls.incrementAndGet()
                bearer("t")
            },
        )
        tools.size shouldBe 3
        calls.get() shouldBe 0

        tools.named("listPets").executeWithJson("{}", context()).success shouldBe true
        tools.named("showPetById").executeWithJson("""{"petId":"1"}""", context()).success shouldBe true

        calls.get() shouldBe 1
        server.requests.filter { it.path.startsWith("/v1/") }.map { it.header("Authorization") } shouldContainExactly
            listOf("Bearer t", "Bearer t")
    }

    "a credential that could not be obtained is not asked again in the run, the next run asks again" {
        servePetstoreApi()
        CapturedLogs.clear()
        val answers = ArrayDeque(listOf(null, bearer("late")))
        val askingContext = context { answers.removeFirst() }
        val tools = provider.provideTools(specConfig(), "PETS", askingContext)

        val refused = tools.named("listPets").executeWithJson("{}", context())
        val again = tools.named("showPetById").executeWithJson("""{"petId":"1"}""", context())

        listOf(refused, again).map { it.errorType } shouldContainExactly listOf("AUTH_MISSING", "AUTH_MISSING")
        answers.size shouldBe 1
        refused.output shouldContain "no credential available for the bound Auth Setting"
        refused.output shouldContain "do not retry"
        refused.output shouldContain "an administrator"
        CapturedLogs.lines.joinToString("\n") shouldContain "no credential available for the bound Auth Setting"
        server.requests.none { it.path.startsWith("/v1/") } shouldBe true

        val nextRun = provider.provideTools(specConfig(), "PETS", askingContext)
        nextRun.named("listPets").executeWithJson("{}", context()).success shouldBe true
        server.requests.single { it.path.startsWith("/v1/") }.header("Authorization") shouldBe "Bearer late"
    }

    "invalid arguments are refused before the credential is asked" {
        servePetstoreApi()
        val calls = AtomicInteger()
        val tools = provider.provideTools(
            specConfig(),
            "PETS",
            context {
                calls.incrementAndGet()
                bearer("t")
            },
        )

        val result = tools.named("showPetById").executeWithJson("{}", context())

        result.errorType shouldBe "INVALID_INPUT"
        calls.get() shouldBe 0
    }

    listOf<Credential?>(bearer("t"), null).forEach { answer ->
        "concurrent calls of a run resolve the credential once (provider answers ${answer?.credentialType})" {
            servePetstoreApi()
            val calls = AtomicInteger()
            val release = CountDownLatch(1)
            val tools = provider.provideTools(
                specConfig(),
                "PETS",
                context {
                    calls.incrementAndGet()
                    release.await()
                    answer
                },
            )

            // UNDISPATCHED runs each call up to its first suspension before the next one starts: the first
            // is resolving, held by the latch, and the three others have all asked for the spec before it ends.
            coroutineScope {
                val results = (1..4).map {
                    async(start = CoroutineStart.UNDISPATCHED) {
                        tools.named("listPets").executeWithJson("{}", context())
                    }
                }
                release.countDown()
                results.awaitAll()
            }.all { it.success } shouldBe (answer != null)

            calls.get() shouldBe 1
        }
    }

    "a cancellation thrown by the provider while the run goes on is AUTH_MISSING" {
        servePetstoreApi()
        val tools = provider.provideTools(
            specConfig(),
            "PETS",
            context { throw CancellationException("flow cancelled") },
        )

        tools.named("listPets").executeWithJson("{}", context()).errorType shouldBe "AUTH_MISSING"
    }

    "cancelling the run interrupts a provider waiting for the user" {
        servePetstoreApi()
        val started = CountDownLatch(1)
        val interrupted = CountDownLatch(1)
        val tools = provider.provideTools(
            specConfig(),
            "PETS",
            context {
                started.countDown()
                try {
                    Thread.sleep(10_000)
                } catch (e: InterruptedException) {
                    interrupted.countDown()
                    throw e
                }
                bearer("t")
            },
        )

        coroutineScope {
            val call = launch(Dispatchers.Default) { tools.named("listPets").executeWithJson("{}", context()) }
            runInterruptible(Dispatchers.IO) { started.await() }
            call.cancelAndJoin()
        }
        // Without the interrupt the sleep runs to its end and the latch stays at 1.
        interrupted.count shouldBe 0L
    }

    "a failing credential provider never escapes: the call fails and the message stays in the logs" {
        servePetstoreApi()
        CapturedLogs.clear()
        val tools = provider.provideTools(specConfig(), "PETS", context { throw IllegalStateException("vault down") })
        tools.size shouldBe 3

        val result = tools.named("listPets").executeWithJson("{}", context())

        result.errorType shouldBe "AUTH_MISSING"
        result.output shouldContain "unexpected error while resolving the credential, see the service logs"
        result.output shouldNotContain "vault down"
        CapturedLogs.lines.joinToString("\n") shouldContain "vault down"
        server.requests.none { it.path.startsWith("/v1/") } shouldBe true
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
