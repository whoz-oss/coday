package io.whozoss.agentos.plugins.http.cache

import io.kotest.core.spec.IsolationMode
import io.kotest.core.spec.style.StringSpec
import io.kotest.engine.spec.tempdir
import io.kotest.engine.spec.tempfile
import io.kotest.matchers.collections.shouldContainExactly
import io.kotest.matchers.shouldBe
import io.kotest.matchers.string.shouldContain
import io.kotest.matchers.string.shouldNotContain
import io.kotest.matchers.string.shouldStartWith
import io.kotest.matchers.types.shouldBeInstanceOf
import io.mockk.every
import io.mockk.mockk
import io.mockk.verify
import io.whozoss.agentos.plugins.http.config.ApiKeyPlacement
import io.whozoss.agentos.plugins.http.config.AuthConfig
import io.whozoss.agentos.plugins.http.config.HttpApiConfig
import io.whozoss.agentos.plugins.http.config.SpecConfig
import io.whozoss.agentos.plugins.http.file.FileReadOutcome
import io.whozoss.agentos.plugins.http.file.FileStamp
import io.whozoss.agentos.plugins.http.file.SpecFileSource
import io.whozoss.agentos.plugins.http.net.FetchOutcome
import io.whozoss.agentos.plugins.http.net.SpecSource
import io.whozoss.agentos.plugins.http.openapi.Fixtures
import io.whozoss.agentos.plugins.http.openapi.ToolNaming
import io.whozoss.agentos.plugins.http.testing.CapturedLogs
import java.io.File
import java.nio.file.Files
import java.nio.file.attribute.FileTime
import java.time.Clock
import java.time.Duration
import java.time.Instant
import java.time.ZoneOffset
import java.util.concurrent.CountDownLatch
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger

class OperationCatalogueCacheUnitSpec : StringSpec({
    isolationMode = IsolationMode.InstancePerLeaf

    val petstore = Fixtures.load("petstore-3.0.yaml")
    val minimal = Fixtures.load("minimal-handwritten.yaml")
    val specUrl = "https://api.example.com/openapi.yaml"
    val urlConfig = HttpApiConfig(
        spec = SpecConfig(url = specUrl, refreshMinutes = 60),
        baseUrl = "https://api.example.com",
    )

    /** A clock the test moves forward explicitly. */
    class MutableClock(var now: Instant = Instant.parse("2026-09-08T10:00:00Z")) : Clock() {
        override fun getZone(): java.time.ZoneId = ZoneOffset.UTC
        override fun withZone(zone: java.time.ZoneId): Clock = this
        override fun instant(): Instant = now
        fun advance(duration: Duration) {
            now = now.plus(duration)
        }
    }

    val clock = MutableClock()
    val fetcher = mockk<SpecSource>()
    val cache = OperationCatalogueCache(fetcher = fetcher, clock = clock)

    fun ready(outcome: CatalogueOutcome): Catalogue = outcome.shouldBeInstanceOf<CatalogueOutcome.Ready>().catalogue

    "loads an inline document without any fetch and keeps it forever" {
        val config = HttpApiConfig(spec = SpecConfig(inline = minimal), baseUrl = "https://api.example.com")
        val catalogue = ready(cache.getOrLoad("MIN", config))
        catalogue.title shouldBe "Minimal"
        catalogue.version shouldBe "1.0.0"
        catalogue.readOnly shouldBe true
        catalogue.operations.map { it.toolSuffix } shouldContainExactly
            listOf("get_tickets", "get_tickets_by_ticket_id")
        clock.advance(Duration.ofDays(30))
        ready(cache.getOrLoad("MIN", config)) shouldBe catalogue
        verify(exactly = 0) { fetcher.fetch(any(), any(), any()) }
    }

    "fetches a URL document once for two calls with the same config" {
        every { fetcher.fetch(specUrl, urlConfig.spec.maxBytes, null) } returns
            FetchOutcome.Fetched(petstore, etag = "\"v1\"")
        val first = ready(cache.getOrLoad("PETS", urlConfig))
        first.title shouldBe "Petstore"
        first.operations.map { it.operationId } shouldContainExactly listOf("listPets", "showPetById", "listStores")
        ready(cache.getOrLoad("PETS", urlConfig)) shouldBe first
        verify(exactly = 1) { fetcher.fetch(any(), any(), any()) }
    }

    "refetches with the etag once the refresh interval has elapsed" {
        every { fetcher.fetch(specUrl, any(), null) } returns FetchOutcome.Fetched(petstore, etag = "\"v1\"")
        every { fetcher.fetch(specUrl, any(), "\"v1\"") } returns FetchOutcome.Fetched(minimal, etag = "\"v2\"")
        ready(cache.getOrLoad("PETS", urlConfig)).title shouldBe "Petstore"
        clock.advance(Duration.ofMinutes(61))
        ready(cache.getOrLoad("PETS", urlConfig)).title shouldBe "Minimal"
        verify(exactly = 1) { fetcher.fetch(specUrl, any(), "\"v1\"") }
    }

    "a 304 keeps the catalogue and restarts the refresh interval" {
        every { fetcher.fetch(specUrl, any(), null) } returns FetchOutcome.Fetched(petstore, etag = "\"v1\"")
        every { fetcher.fetch(specUrl, any(), "\"v1\"") } returns FetchOutcome.NotModified
        val first = ready(cache.getOrLoad("PETS", urlConfig))
        clock.advance(Duration.ofMinutes(61))
        ready(cache.getOrLoad("PETS", urlConfig)) shouldBe first
        clock.advance(Duration.ofMinutes(30))
        ready(cache.getOrLoad("PETS", urlConfig)) shouldBe first
        verify(exactly = 1) { fetcher.fetch(specUrl, any(), "\"v1\"") }
    }

    "a refresh failure serves the stale catalogue and reports the reason" {
        every { fetcher.fetch(specUrl, any(), null) } returns FetchOutcome.Fetched(petstore, etag = "\"v1\"")
        every { fetcher.fetch(specUrl, any(), "\"v1\"") } returns FetchOutcome.Failed("HTTP 503")
        val first = ready(cache.getOrLoad("PETS", urlConfig))
        clock.advance(Duration.ofMinutes(61))
        val stale = cache.getOrLoad("PETS", urlConfig).shouldBeInstanceOf<CatalogueOutcome.Ready>()
        stale.catalogue shouldBe first
        stale.staleReason shouldContain "HTTP 503"
    }

    "a failure without a cached entry is Failed and is retried on the next call" {
        every { fetcher.fetch(specUrl, any(), null) } returns
            FetchOutcome.Failed("HTTP 503") andThen FetchOutcome.Fetched(petstore, null)
        cache.getOrLoad("PETS", urlConfig).shouldBeInstanceOf<CatalogueOutcome.Failed>().reason shouldContain "HTTP 503"
        cache.peek(urlConfig) shouldBe null
        ready(cache.getOrLoad("PETS", urlConfig)).title shouldBe "Petstore"
        verify(exactly = 2) { fetcher.fetch(any(), any(), any()) }
    }

    "too many operations is a Failed outcome naming the limit" {
        every { fetcher.fetch(specUrl, any(), null) } returns FetchOutcome.Fetched(petstore, null)
        val failed = cache.getOrLoad("PETS", urlConfig.copy(maxTools = 2)).shouldBeInstanceOf<CatalogueOutcome.Failed>()
        failed.reason shouldBe "3 operations selected, exceeds maxTools=2"
    }

    "an unreadable document is a Failed outcome" {
        every { fetcher.fetch(specUrl, any(), null) } returns FetchOutcome.Fetched("swagger: '2.0'\npaths: {}", null)
        val failed = cache.getOrLoad("PETS", urlConfig).shouldBeInstanceOf<CatalogueOutcome.Failed>()
        failed.reason shouldContain "OpenAPI 3.x"
    }

    "refreshMinutes 0 never refetches" {
        val config = urlConfig.copy(spec = urlConfig.spec.copy(refreshMinutes = 0))
        every { fetcher.fetch(specUrl, any(), null) } returns FetchOutcome.Fetched(petstore, null)
        val first = ready(cache.getOrLoad("PETS", config))
        clock.advance(Duration.ofDays(365))
        ready(cache.getOrLoad("PETS", config)) shouldBe first
        verify(exactly = 1) { fetcher.fetch(any(), any(), any()) }
    }

    "allowMutations is part of the identity and of the catalogue" {
        every { fetcher.fetch(specUrl, any(), null) } returns FetchOutcome.Fetched(petstore, null)
        val readOnly = ready(cache.getOrLoad("PETS", urlConfig))
        val writable = ready(cache.getOrLoad("PETS", urlConfig.copy(allowMutations = true)))
        readOnly.readOnly shouldBe true
        writable.readOnly shouldBe false
        writable.operations.size shouldBe 6
        verify(exactly = 2) { fetcher.fetch(any(), any(), any()) }
    }

    "uses the document server URL when the config has no baseUrl" {
        val config = HttpApiConfig(spec = SpecConfig(inline = petstore))
        ready(cache.getOrLoad("PETS", config)).baseUrl shouldBe "https://petstore.example.com/v1"
    }

    "an explicit baseUrl wins over the document server URL" {
        val config = HttpApiConfig(spec = SpecConfig(inline = petstore), baseUrl = "https://corp.example.com/api")
        ready(cache.getOrLoad("PETS", config)).baseUrl shouldBe "https://corp.example.com/api"
    }

    "substitutes server variables before checking the document server URL" {
        val document = """
            openapi: 3.0.0
            info: { title: t, version: "1" }
            servers:
              - url: https://{region}.example.com/{basePath}
                variables:
                  region: { default: eu }
                  basePath: { default: v2 }
            paths: {}
        """.trimIndent()
        val config = HttpApiConfig(spec = SpecConfig(inline = document))
        ready(cache.getOrLoad("VARS", config)).baseUrl shouldBe "https://eu.example.com/v2"
    }

    listOf(
        "no servers" to "openapi: 3.0.0\ninfo: { title: t, version: '1' }\npaths: {}\n",
        "a relative server URL" to
            "openapi: 3.0.0\ninfo: { title: t, version: '1' }\nservers: [{ url: /v1 }]\npaths: {}\n",
    ).forEach { (label, document) ->
        "a document with $label and no baseUrl is Failed naming the missing key" {
            val config = HttpApiConfig(spec = SpecConfig(inline = document))
            cache.getOrLoad("BARE", config).shouldBeInstanceOf<CatalogueOutcome.Failed>().reason shouldBe
                "'baseUrl' is required because the OpenAPI document declares no absolute server URL"
        }
    }

    "a document server URL refused by the policy is Failed with the policy reason" {
        val document =
            "openapi: 3.0.0\ninfo: { title: t, version: '1' }\nservers: [{ url: http://api.example.com }]\npaths: {}\n"
        val config = HttpApiConfig(spec = SpecConfig(inline = document))
        cache.getOrLoad("HTTP", config).shouldBeInstanceOf<CatalogueOutcome.Failed>().reason shouldBe
            "'baseUrl' is required because the OpenAPI document server URL must use the https scheme, got 'http'"
    }

    "a document server URL with a port the HTTP client refuses is Failed with the policy reason" {
        val document = "openapi: 3.0.0\ninfo: { title: t, version: '1' }\n" +
            "servers: [{ url: https://api.example.com:99999 }]\npaths: {}\n"
        val config = HttpApiConfig(spec = SpecConfig(inline = document))
        val reason = cache.getOrLoad("PORT", config).shouldBeInstanceOf<CatalogueOutcome.Failed>().reason
        reason shouldStartWith "'baseUrl' is required because the OpenAPI document server URL"
        reason shouldContain "port"
    }

    "a document server URL with a variable without default is Failed naming the variable" {
        val document = """
            openapi: 3.0.0
            info: { title: t, version: "1" }
            servers:
              - url: https://{region}.example.com/{basePath}
                variables:
                  region: { enum: [eu, us] }
                  basePath: { default: v2 }
            paths: {}
        """.trimIndent()
        val config = HttpApiConfig(spec = SpecConfig(inline = document))
        cache.getOrLoad("VAR", config).shouldBeInstanceOf<CatalogueOutcome.Failed>().reason shouldBe
            "'baseUrl' is required because the OpenAPI document server URL keeps an unresolved variable '{region}'"
    }

    "loads a file document and reloads it once its lastModified changes" {
        val file = tempfile(suffix = ".yaml")
        file.writeText(petstore)
        val config = HttpApiConfig(spec = SpecConfig(file = file.absolutePath), baseUrl = "https://api.example.com")
        ready(cache.getOrLoad("FILE", config)).title shouldBe "Petstore"
        file.writeText(minimal)
        Files.setLastModifiedTime(file.toPath(), FileTime.fromMillis(file.lastModified() + 5_000))
        ready(cache.getOrLoad("FILE", config)).title shouldBe "Minimal"
        verify(exactly = 0) { fetcher.fetch(any(), any(), any()) }
    }

    "a file document whose stamp is unchanged is served from the cache without being read again" {
        val files = mockk<SpecFileSource>()
        val stamp = FileStamp(lastModifiedMillis = 1_000, size = 42)
        every { files.stamp("/etc/specs/pets.yaml") } returns stamp
        every { files.read("/etc/specs/pets.yaml", any()) } returns FileReadOutcome.Read(petstore)
        val fileCache = OperationCatalogueCache(fetcher = fetcher, fileSource = files, clock = clock)
        val config = HttpApiConfig(spec = SpecConfig(file = "/etc/specs/pets.yaml"), baseUrl = "https://a.example.com")
        val first = ready(fileCache.getOrLoad("FILE", config))
        clock.advance(Duration.ofDays(30))
        ready(fileCache.getOrLoad("FILE", config)) shouldBe first
        verify(exactly = 1) { files.read(any(), any()) }
    }

    "the file stamp is part of the cache key: a changed stamp is a miss, then a read under the new key" {
        val files = mockk<SpecFileSource>()
        every { files.stamp("/etc/specs/pets.yaml") } returns FileStamp(lastModifiedMillis = 1_000, size = 42)
        every { files.read("/etc/specs/pets.yaml", any()) } returns
            FileReadOutcome.Read(petstore) andThen FileReadOutcome.Read(minimal)
        val fileCache = OperationCatalogueCache(fetcher = fetcher, fileSource = files, clock = clock)
        val config = HttpApiConfig(spec = SpecConfig(file = "/etc/specs/pets.yaml"), baseUrl = "https://a.example.com")
        ready(fileCache.getOrLoad("FILE", config)).title shouldBe "Petstore"
        every { files.stamp("/etc/specs/pets.yaml") } returns FileStamp(lastModifiedMillis = 2_000, size = 42)
        fileCache.peek(config) shouldBe null
        ready(fileCache.getOrLoad("FILE", config)).title shouldBe "Minimal"
        ready(fileCache.getOrLoad("FILE", config)).title shouldBe "Minimal"
        verify(exactly = 2) { files.read(any(), any()) }
    }

    "a missing file is Failed with a generic reason, the detail logged at ERROR, and nothing is cached" {
        CapturedLogs.clear()
        val missing = SpecConfig(file = "/nonexistent/pets.yaml")
        val config = HttpApiConfig(spec = missing, baseUrl = "https://a.example.com")
        cache.getOrLoad("FILE", config).shouldBeInstanceOf<CatalogueOutcome.Failed>().reason shouldBe
            "document file cannot be loaded, see the service logs"
        CapturedLogs.lines.count { "ERROR" in it && "document file does not exist" in it } shouldBe 1
        cache.peek(config) shouldBe null
    }

    "a malformed file document is Failed with a generic reason and the log never echoes its content" {
        CapturedLogs.clear()
        val sentinel = "SUPER-SECRET-do-not-leak"
        val file = tempfile(suffix = ".yaml")
        file.writeText("openapi: 3.0.0\ninfo: { title: t, version: \"1\" }\npassword: [$sentinel\n")
        val config = HttpApiConfig(spec = SpecConfig(file = file.absolutePath), baseUrl = "https://a.example.com")
        cache.getOrLoad("FILE", config).shouldBeInstanceOf<CatalogueOutcome.Failed>().reason shouldBe
            "document file cannot be loaded, see the service logs"
        val logged = CapturedLogs.lines.joinToString("\n")
        logged shouldContain "OpenAPI document cannot be parsed"
        logged shouldNotContain sentinel
        cache.peek(config) shouldBe null
    }

    "a file that disappears after a load is Failed with the file reason and its catalogue is no longer served" {
        val file = File(tempdir(), "pets.yaml")
        file.writeText(petstore)
        val config = HttpApiConfig(spec = SpecConfig(file = file.absolutePath), baseUrl = "https://api.example.com")
        ready(cache.getOrLoad("FILE", config)).title shouldBe "Petstore"
        file.delete() shouldBe true
        cache.getOrLoad("FILE", config).shouldBeInstanceOf<CatalogueOutcome.Failed>().reason shouldBe
            "document file cannot be loaded, see the service logs"
        cache.peek(config) shouldBe null
    }

    "an oversize file is Failed with a generic reason, the size logged at ERROR only" {
        CapturedLogs.clear()
        val file = tempfile(suffix = ".yaml")
        file.writeText(petstore)
        val config = HttpApiConfig(
            spec = SpecConfig(file = file.absolutePath, maxBytes = SpecConfig.MIN_MAX_BYTES),
            baseUrl = "https://api.example.com",
        )
        cache.getOrLoad("FILE", config).shouldBeInstanceOf<CatalogueOutcome.Failed>().reason shouldBe
            "document file cannot be loaded, see the service logs"
        CapturedLogs.lines.count { "ERROR" in it && "larger than the allowed 1024 bytes" in it } shouldBe 1
    }

    "takes the API key placement from the document when the config auth is untouched" {
        val keyed = Fixtures.load("api-key-scheme.yaml")
        val untouched = ready(cache.getOrLoad("KEYED", HttpApiConfig(spec = SpecConfig(inline = keyed))))
        untouched.auth shouldBe AuthConfig(apiKeyIn = ApiKeyPlacement.QUERY, apiKeyName = "api_key")
        untouched.authFromDocument shouldBe true
        val explicit = AuthConfig(apiKeyIn = ApiKeyPlacement.HEADER, apiKeyName = "X-Token")
        val explicitConfig = HttpApiConfig(spec = SpecConfig(inline = keyed), auth = explicit)
        val configured = ready(cache.getOrLoad("KEYED", explicitConfig))
        configured.auth shouldBe explicit
        configured.authFromDocument shouldBe false
        val plain = ready(cache.getOrLoad("PETS", HttpApiConfig(spec = SpecConfig(inline = petstore))))
        plain.auth shouldBe AuthConfig()
        plain.authFromDocument shouldBe false
    }

    "reserves the header of the document API key scheme when the config auth is untouched" {
        val tokenHeader = """
            openapi: 3.0.3
            info: { title: Token, version: "1" }
            servers: [{ url: https://token.example.com }]
            components:
              securitySchemes:
                Token: { type: apiKey, in: header, name: X-Api-Token }
            paths:
              /optional:
                get:
                  operationId: optionalToken
                  parameters:
                    - { name: X-Api-Token, in: header, schema: { type: string } }
                    - { name: X-Trace, in: header, schema: { type: string } }
                  responses: { '200': { description: Ok } }
              /required:
                get:
                  operationId: requiredToken
                  parameters:
                    - { name: X-Api-Token, in: header, required: true, schema: { type: string } }
                  responses: { '200': { description: Ok } }
        """.trimIndent()
        CapturedLogs.clear()
        val untouched = ready(cache.getOrLoad("TOKEN", HttpApiConfig(spec = SpecConfig(inline = tokenHeader))))
        untouched.auth shouldBe AuthConfig(apiKeyIn = ApiKeyPlacement.HEADER, apiKeyName = "X-Api-Token")
        untouched.operations.map { it.operationId } shouldContainExactly listOf("optionalToken")
        untouched.operations.single().parameters.map { it.name } shouldContainExactly listOf("X-Trace")
        untouched.warnings.map { it.reason } shouldContainExactly listOf(
            "required header parameter 'X-Api-Token' is reserved and cannot be set by the agent",
            "header parameter 'X-Api-Token' ignored: is reserved and cannot be set by the agent",
        )
        CapturedLogs.lines.single { "WARN" in it && "header parameter 'X-Api-Token' ignored" in it } shouldContain
            "HTTP_API 'TOKEN': 1 operation(s) exposed, 2 warning(s)"
        val explicit = AuthConfig(apiKeyIn = ApiKeyPlacement.QUERY, apiKeyName = "token")
        val explicitConfig = HttpApiConfig(spec = SpecConfig(inline = tokenHeader), auth = explicit)
        val configured = ready(cache.getOrLoad("TOKEN", explicitConfig))
        configured.operations.map { it.operationId } shouldContainExactly listOf("optionalToken", "requiredToken")
        configured.operations.first().parameters.map { it.name } shouldContainExactly listOf("X-Api-Token", "X-Trace")
        configured.warnings shouldBe emptyList()
    }

    "a default header equal to the API key header of the config is Failed" {
        val auth = AuthConfig(apiKeyIn = ApiKeyPlacement.HEADER, apiKeyName = "X-Token")
        val config = HttpApiConfig(
            spec = SpecConfig(inline = minimal),
            baseUrl = "https://api.example.com",
            auth = auth,
            defaultHeaders = mapOf("x-token" to "x"),
        )
        val failed = cache.getOrLoad("MIN", config).shouldBeInstanceOf<CatalogueOutcome.Failed>()
        failed.reason shouldBe
            "'defaultHeaders' must not set 'x-token': it is the API key header 'X-Token' of the config"
        cache.peek(config) shouldBe null
        val inQuery = config.copy(auth = AuthConfig(apiKeyIn = ApiKeyPlacement.QUERY, apiKeyName = "X-Token"))
        ready(cache.getOrLoad("MIN", inQuery)).auth shouldBe inQuery.auth
    }

    "a default header equal to the API key header of the document security scheme is Failed" {
        val keyed = Fixtures.load("api-key-header-scheme.yaml")
        val config = HttpApiConfig(spec = SpecConfig(inline = keyed), defaultHeaders = mapOf("X-Api-Key" to "x"))
        val failed = cache.getOrLoad("KEYED", config).shouldBeInstanceOf<CatalogueOutcome.Failed>()
        failed.reason shouldBe "'defaultHeaders' must not set 'X-Api-Key': it is the API key header 'X-Api-Key' " +
            "of the document security scheme"
        ready(cache.getOrLoad("KEYED", config.copy(defaultHeaders = mapOf("X-Trace" to "x"))))
    }

    "a default header named like the default API key header is accepted when the key goes elsewhere" {
        val keyed = Fixtures.load("api-key-scheme.yaml")
        val config = HttpApiConfig(spec = SpecConfig(inline = keyed), defaultHeaders = mapOf("X-API-Key" to "x"))
        ready(cache.getOrLoad("KEYED", config)).auth shouldBe
            AuthConfig(apiKeyIn = ApiKeyPlacement.QUERY, apiKeyName = "api_key")
    }

    "accepts a document produced by the HTTP integration converter and names the tool after the endpoint" {
        val converted = Fixtures.load("converted-my-calendar.yaml")
        val catalogue = ready(cache.getOrLoad("MY_CALENDAR", HttpApiConfig(spec = SpecConfig(inline = converted))))
        catalogue.baseUrl shouldBe "https://www.googleapis.com/calendar/v3"
        catalogue.warnings shouldBe emptyList()
        val events = catalogue.operations.single()
        ToolNaming.toolName(configName = "MY_CALENDAR", suffix = events.toolSuffix) shouldBe "MY_CALENDAR__getEvents"
        events.description shouldContain "GET /calendars/{calendarId}/events — List events from a Google Calendar."
        events.parameters.map { it.name to it.required } shouldBe
            listOf("calendarId" to true, "timeMin" to false, "maxResults" to false)
    }

    "peek returns the cached catalogue without loading" {
        cache.peek(urlConfig) shouldBe null
        every { fetcher.fetch(specUrl, any(), null) } returns FetchOutcome.Fetched(petstore, null)
        val loaded = ready(cache.getOrLoad("PETS", urlConfig))
        cache.peek(urlConfig) shouldBe loaded
        verify(exactly = 1) { fetcher.fetch(any(), any(), any()) }
    }

    "concurrent calls for the same config fetch once" {
        val started = CountDownLatch(1)
        val fetches = AtomicInteger()
        every { fetcher.fetch(specUrl, any(), null) } answers {
            fetches.incrementAndGet()
            started.await(5, TimeUnit.SECONDS)
            FetchOutcome.Fetched(petstore, null)
        }
        val pool = Executors.newFixedThreadPool(4)
        val futures = (1..4).map { pool.submit<CatalogueOutcome> { cache.getOrLoad("PETS", urlConfig) } }
        Thread.sleep(200)
        started.countDown()
        futures.forEach { ready(it.get(5, TimeUnit.SECONDS)).title shouldBe "Petstore" }
        pool.shutdownNow()
        fetches.get() shouldBe 1
    }

    "evicts the least recently used entry beyond the capacity" {
        val small = OperationCatalogueCache(fetcher = fetcher, clock = clock, maxEntries = 2)
        every { fetcher.fetch(any(), any(), null) } returns FetchOutcome.Fetched(petstore, null)
        val a = urlConfig.copy(baseUrl = "https://a.example.com")
        val b = urlConfig.copy(baseUrl = "https://b.example.com")
        val c = urlConfig.copy(baseUrl = "https://c.example.com")
        ready(small.getOrLoad("A", a))
        ready(small.getOrLoad("B", b))
        ready(small.getOrLoad("A", a))
        ready(small.getOrLoad("C", c))
        small.peek(b) shouldBe null
        small.peek(a)?.baseUrl shouldBe "https://a.example.com"
        small.peek(c)?.baseUrl shouldBe "https://c.example.com"
    }
})
