package io.whozoss.agentos.plugins.http

import io.kotest.core.spec.IsolationMode
import io.kotest.core.spec.style.StringSpec
import io.kotest.matchers.collections.shouldContainExactly
import io.kotest.matchers.longs.shouldBeLessThan
import io.kotest.matchers.maps.shouldContainExactly
import io.kotest.matchers.nulls.shouldBeNull
import io.kotest.matchers.shouldBe
import io.kotest.matchers.string.shouldContain
import io.kotest.matchers.string.shouldContainIgnoringCase
import io.kotest.matchers.string.shouldNotContain
import io.kotest.matchers.string.shouldStartWith
import io.whozoss.agentos.plugins.http.auth.AuthHeaderSpec
import io.whozoss.agentos.plugins.http.net.HttpClientHolder
import io.whozoss.agentos.plugins.http.openapi.OperationDescriptor
import io.whozoss.agentos.plugins.http.testing.ExecutionFixture
import io.whozoss.agentos.plugins.http.testing.TestHttpServer
import io.whozoss.agentos.plugins.http.testing.TestResponse
import io.whozoss.agentos.sdk.tool.ConfirmationMode
import io.whozoss.agentos.sdk.tool.ToolExecutionResult
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Semaphore
import kotlinx.coroutines.withContext
import java.util.concurrent.atomic.AtomicInteger

/**
 * A semaphore whose `acquire` cannot be cancelled and completes after [grantAfterMillis]: reproduces the
 * kotlinx "asynchronous timeout and resources" race deterministically, the permit being granted right as
 * the wait for it times out.
 */
private class LateSemaphore(private val delegate: Semaphore, private val grantAfterMillis: Long) : Semaphore {
    override val availablePermits: Int get() = delegate.availablePermits

    override suspend fun acquire() {
        withContext(NonCancellable) { delay(grantAfterMillis) }
        delegate.acquire()
    }

    override fun tryAcquire(): Boolean = delegate.tryAcquire()

    override fun release(): Unit = delegate.release()
}

class HttpApiToolExecutionSpec : StringSpec({
    isolationMode = IsolationMode.InstancePerLeaf

    val holder = HttpClientHolder(ExecutionFixture.policy)
    val server = TestHttpServer()
    afterSpec {
        server.close()
        holder.shutdown()
    }
    val context = ExecutionFixture.context()
    val config = ExecutionFixture.config(baseUrl = "${server.baseUrl}/api")
    val tools = ExecutionFixture.tools(config, ExecutionFixture.runtime(config, holder.client))
    fun tool(operationId: String): HttpApiTool = checkNotNull(tools[operationId])

    fun ok(body: String, contentType: String? = "application/json"): TestResponse =
        TestResponse(body = body, contentType = contentType)

    suspend fun call(operationId: String, args: String?): ToolExecutionResult =
        tool(operationId).executeWithJson(args, context)

    "names the tool after the config and the operation" {
        tools.keys.map { tool(it).name } shouldContainExactly
            listOf(
                "ITEMS__listItems", "ITEMS__createItem", "ITEMS__deleteItem", "ITEMS__showItem", "ITEMS__updateItem",
                "ITEMS__nestedOp", "ITEMS__tenantInfo", "ITEMS__createToken",
            )
        tool("showItem").description shouldContain "GET /items/{id}"
        tool("showItem").inputSchema shouldContain "\"id\""
    }

    "GET tools need no confirmation, write tools need it every time with a fixed instruction" {
        tool("showItem").getConfirmationMode() shouldBe ConfirmationMode.NONE
        tool("showItem").getConfirmationInstructions() shouldBe ""
        listOf("createItem", "updateItem", "deleteItem", "createToken").forEach {
            tool(it).getConfirmationMode() shouldBe ConfirmationMode.EVERY_TIME
            tool(it).getConfirmationInstructions() shouldContain "HTTP_API integration 'ITEMS'"
            tool(it).getConfirmationInstructions() shouldContain "explicit user consent"
        }
    }

    "encodes a path parameter as a single segment" {
        server.handler = { ok("""{"item":{"id":"a/b","name":"n","secret":"s"}}""") }
        val result = call("showItem", """{"id":"a/b"}""")
        result.success shouldBe true
        server.requests.single().path shouldBe "/api/items/a%2Fb"
        server.requests.single().method shouldBe "GET"
    }

    "substitutes several parameters inside one segment" {
        server.handler = { ok("{}") }
        call("nestedOp", """{"a":"x y","b":"42"}""").success shouldBe true
        server.requests.single().path shouldBe "/api/nested/x%20y/42.json"
    }

    "sends array query parameters repeated and skips null values" {
        server.handler = { ok("""{"items":[]}""") }
        call("listItems", """{"q":"open tickets","tags":["a","b"],"limit":null}""").success shouldBe true
        server.requests.single().query shouldBe "q=open%20tickets&tags=a&tags=b"
    }

    "sends header parameters and omits an absent optional one" {
        server.handler = { ok("""{"items":[]}""") }
        call("listItems", """{"X-Request-Id":"req-1"}""").success shouldBe true
        server.requests[0].header("X-Request-Id") shouldBe "req-1"
        call("listItems", "{}").success shouldBe true
        server.requests[1].header("X-Request-Id").shouldBeNull()
        call("tenantInfo", """{"X-Tenant":"acme"}""").success shouldBe true
        server.requests[2].header("X-Tenant") shouldBe "acme"
    }

    "a header set by defaultHeaders is not a tool argument and the configured value is sent" {
        server.handler = { ok("""{"items":[]}""") }
        val pinned = config.copy(defaultHeaders = mapOf("X-Request-Id" to "admin"))
        val pinnedTools = ExecutionFixture.tools(pinned, ExecutionFixture.runtime(pinned, holder.client))
        val listItems = checkNotNull(pinnedTools["listItems"])
        listItems.inputSchema shouldNotContain "X-Request-Id"
        listItems.executeWithJson("""{"X-Request-Id":"evil"}""", context).success shouldBe true
        server.requests.single().header("X-Request-Id") shouldBe "admin"
    }

    "a header argument never overrides a defaultHeaders entry, even when the descriptor still exposes it" {
        server.handler = { ok("""{"items":[]}""") }
        val pinned = config.copy(defaultHeaders = mapOf("x-request-id" to "admin"))
        // descriptors curated without the default header, as after a config change not yet reloaded
        val staleTools = ExecutionFixture.tools(config, ExecutionFixture.runtime(pinned, holder.client))
        val listItems = checkNotNull(staleTools["listItems"])
        listItems.inputSchema shouldContain "X-Request-Id"
        listItems.executeWithJson("""{"X-Request-Id":"evil"}""", context).success shouldBe true
        server.requests.single().header("X-Request-Id") shouldBe "admin"
    }

    "a missing required header parameter is INVALID_INPUT without any request" {
        val result = call("tenantInfo", "{}")
        result.errorType shouldBe "INVALID_INPUT"
        result.output shouldContain "'X-Tenant'"
        server.requests.size shouldBe 0
    }

    listOf(
        """a\r\nX-Injected: 1""",
        """a\nb""",
        """tab\tis\u0007bell""",
        """caf\u00e9""",
    ).forEach { value ->
        "a header value that is not a single printable line is INVALID_INPUT without any request: $value" {
            val result = call("tenantInfo", """{"X-Tenant":"$value"}""")
            result.errorType shouldBe "INVALID_INPUT"
            result.output shouldContain "'X-Tenant'"
            server.requests.size shouldBe 0
        }
    }

    "a missing required path parameter is INVALID_INPUT without any request" {
        val result = call("showItem", "{}")
        result.success shouldBe false
        result.errorType shouldBe "INVALID_INPUT"
        result.output shouldContain "'id'"
        server.requests.size shouldBe 0
    }

    "a dot-dot path parameter is refused without any request" {
        val result = call("showItem", """{"id":".."}""")
        result.errorType shouldBe "URL_POLICY_REJECTED"
        server.requests.size shouldBe 0
    }

    "invalid JSON arguments are INVALID_INPUT" {
        call("listItems", "not json").errorType shouldBe "INVALID_INPUT"
        call("listItems", "[1]").errorType shouldBe "INVALID_INPUT"
        server.requests.size shouldBe 0
    }

    "null or blank arguments mean no arguments" {
        server.handler = { ok("""{"items":[]}""") }
        call("listItems", null).success shouldBe true
        call("listItems", "  ").success shouldBe true
        server.requests.map { it.query } shouldContainExactly listOf(null, null)
    }

    "a 200 JSON answer is shaped and the metadata carries the path only" {
        val body = """{"item":{"id":"42","name":"Widget","secret":"hidden"}}"""
        server.handler = { ok(body) }
        val result = call("showItem", """{"id":"42"}""")
        result.success shouldBe true
        result.output shouldBe """{"item":{"id":"42","name":"Widget"}}"""
        result.metadata shouldContainExactly mapOf(
            "status" to 200,
            "contentType" to "application/json",
            "bytes" to body.length,
            "truncated" to false,
            "path" to "/api/items/42",
        )
        result.metadata.values.joinToString() shouldNotContain "127.0.0.1"
    }

    "renders YAML when the operation asks for it" {
        server.handler = { ok("""{"items":[{"id":1,"secret":"s"}]}""") }
        call("listItems", null).output shouldBe "items:\n- id: 1\n"
    }

    "sends the default Accept and User-Agent headers, overridable by defaultHeaders" {
        server.handler = { ok("{}") }
        call("showItem", """{"id":"1"}""")
        server.requests.single().header("Accept") shouldBe "application/json"
        server.requests.single().header("User-Agent") shouldBe "AgentOS-HTTP_API/1"
        val custom = config.copy(defaultHeaders = mapOf("accept" to "application/vnd.custom+json", "X-Trace" to "t1"))
        val customTools = ExecutionFixture.tools(custom, ExecutionFixture.runtime(custom, holder.client))
        checkNotNull(customTools["showItem"]).executeWithJson("""{"id":"1"}""", context)
        server.requests[1].header("Accept") shouldBe "application/vnd.custom+json"
        server.requests[1].header("X-Trace") shouldBe "t1"
    }

    "sends the auth header, and a query auth as the last query parameter" {
        server.handler = { ok("{}") }
        val header = AuthHeaderSpec.Header("Authorization", "Bearer t0k")
        val headerRuntime = ExecutionFixture.runtime(config, holder.client, authSpec = header)
        val headerTools = ExecutionFixture.tools(config, headerRuntime)
        checkNotNull(headerTools["listItems"]).executeWithJson("""{"q":"x"}""", context)
        server.requests[0].header("Authorization") shouldBe "Bearer t0k"
        val query = AuthHeaderSpec.Query("api_key", "k3y")
        val queryRuntime = ExecutionFixture.runtime(config, holder.client, authSpec = query)
        val queryTools = ExecutionFixture.tools(config, queryRuntime)
        checkNotNull(queryTools["listItems"]).executeWithJson("""{"q":"x"}""", context)
        server.requests[1].query shouldBe "q=x&api_key=k3y"
    }

    "a missing credential is AUTH_MISSING without any request" {
        val missing = AuthHeaderSpec.Missing("no credential available for the bound Auth Setting")
        val missingRuntime = ExecutionFixture.runtime(config, holder.client, authSpec = missing)
        val missingTools = ExecutionFixture.tools(config, missingRuntime)
        val result = checkNotNull(missingTools["showItem"]).executeWithJson("""{"id":"1"}""", context)
        result.errorType shouldBe "AUTH_MISSING"
        result.output shouldContain "no credential available"
        server.requests.size shouldBe 0
    }

    "caps the output with the truncation marker" {
        val items = (1..200).joinToString(",") { """{"id":$it,"name":"item number $it"}""" }
        server.handler = { ok("""{"items":[$items]}""") }
        val result = call("nestedOp", """{"a":"1","b":"2"}""")
        result.success shouldBe true
        result.output shouldContain "... [truncated: showing 500 of "
        result.metadata["truncated"] shouldBe true
    }

    "caps the body read at four times the character cap and flags it" {
        server.handler = { ok("x".repeat(5000), contentType = "text/plain") }
        val result = call("nestedOp", """{"a":"1","b":"2"}""")
        result.success shouldBe true
        result.metadata["bytes"] shouldBe 2000
        result.metadata["truncated"] shouldBe true
    }

    "does not return a binary body" {
        server.handler = { TestResponse(rawBody = ByteArray(300) { 7 }, contentType = "application/octet-stream") }
        val result = call("showItem", """{"id":"1"}""")
        result.success shouldBe true
        result.output shouldBe "binary response (application/octet-stream, 300 bytes) not returned"
    }

    "POST sends the body as JSON with the exact content type" {
        server.handler = { TestResponse(status = 201, body = """{"id":7}""") }
        val result = call("createItem", """{"body":{"name":"Widget","tags":["a"]}}""")
        result.success shouldBe true
        result.output shouldBe "Created\n{\"id\":7}"
        result.metadata["status"] shouldBe 201
        val request = server.requests.single()
        request.method shouldBe "POST"
        request.header("Content-Type") shouldBe "application/json; charset=utf-8"
        request.body shouldBe """{"name":"Widget","tags":["a"]}"""
    }

    "POST encodes a form body, nested values as JSON" {
        server.handler = { ok("""{"access_token":"x"}""") }
        val body = """{"body":{"grant_type":"client_credentials","scope":"a b","meta":{"x":1},"skip":null}}"""
        call("createToken", body).success shouldBe true
        val request = server.requests.single()
        request.header("Content-Type") shouldBe "application/x-www-form-urlencoded"
        request.body shouldBe "grant_type=client_credentials&scope=a%20b&meta=%7B%22x%22%3A1%7D"
    }

    "a form body that is not an object is INVALID_INPUT" {
        call("createToken", """{"body":"grant_type=x"}""").errorType shouldBe "INVALID_INPUT"
        server.requests.size shouldBe 0
    }

    "a missing required body is INVALID_INPUT without any request" {
        val result = call("createItem", "{}")
        result.errorType shouldBe "INVALID_INPUT"
        result.output shouldContain "'body'"
        server.requests.size shouldBe 0
    }

    "an optional body may be omitted on PUT" {
        server.handler = { ok("""{"id":"1"}""") }
        call("updateItem", """{"id":"1"}""").success shouldBe true
        server.requests.single().body shouldBe ""
    }

    "DELETE sends no body and renders 204 as No content" {
        server.handler = { TestResponse(status = 204) }
        val result = call("deleteItem", """{"id":"1"}""")
        result.success shouldBe true
        result.output shouldBe "No content"
        server.requests.single().body shouldBe ""
    }

    "renders 202 as Accepted with the body when present" {
        server.handler = { TestResponse(status = 202, body = """{"job":"j1"}""") }
        call("createItem", """{"body":{}}""").output shouldBe "Accepted\n{\"job\":\"j1\"}"
    }

    "a 401 is UNAUTHORIZED after exactly one request, on GET and on POST" {
        server.handler = { TestResponse(status = 401, body = """{"error":"bad token"}""") }
        val get = call("showItem", """{"id":"1"}""")
        get.errorType shouldBe "UNAUTHORIZED"
        get.output shouldContainIgnoringCase "do not retry"
        get.output shouldContain "administrator"
        get.metadata["status"] shouldBe 401
        val post = call("createItem", """{"body":{}}""")
        post.errorType shouldBe "UNAUTHORIZED"
        server.requests.size shouldBe 2
    }

    "a 403 is FORBIDDEN" {
        server.handler = { TestResponse(status = 403, body = "") }
        call("showItem", """{"id":"1"}""").errorType shouldBe "FORBIDDEN"
    }

    "a 429 is RATE_LIMITED telling the model to wait the Retry-After seconds and not to hammer" {
        server.handler = { TestResponse(status = 429, body = "", headers = mapOf("Retry-After" to "30")) }
        val result = call("showItem", """{"id":"1"}""")
        result.errorType shouldBe "RATE_LIMITED"
        result.output shouldContain "wait 30 seconds before calling it again"
        result.output shouldContain "do not hammer"
        server.requests.size shouldBe 1
    }

    "a 429 with an HTTP-date Retry-After tells the model to wait until that date" {
        val date = "Wed, 21 Oct 2026 07:28:00 GMT"
        server.handler = { TestResponse(status = 429, body = "", headers = mapOf("Retry-After" to date)) }
        val result = call("showItem", """{"id":"1"}""")
        result.errorType shouldBe "RATE_LIMITED"
        result.output shouldContain "wait until $date before calling it again"
        result.output shouldNotContain "GMT seconds"
    }

    "a 429 without Retry-After still tells the model to wait and not to hammer" {
        server.handler = { TestResponse(status = 429, body = "") }
        val result = call("showItem", """{"id":"1"}""")
        result.errorType shouldBe "RATE_LIMITED"
        result.output shouldContain "wait before calling it again"
        result.output shouldContain "do not hammer"
    }

    val clientErrors = mapOf(
        404 to """{"error":"no such item"}""",
        409 to """{"error":"conflict"}""",
        422 to """{"errors":["name"]}""",
    )
    clientErrors.forEach { (status, body) ->
        "a $status is HTTP_CLIENT_ERROR carrying the body" {
            server.handler = { TestResponse(status = status, body = body) }
            val result = call("showItem", """{"id":"1"}""")
            result.errorType shouldBe "HTTP_CLIENT_ERROR"
            result.output shouldContain "HTTP $status"
            result.output shouldContain body
            result.metadata["status"] shouldBe status
        }
    }

    "a 4xx body is capped at 2000 characters" {
        server.handler = { TestResponse(status = 400, body = "e".repeat(3000)) }
        val output = call("showItem", """{"id":"1"}""").output
        output shouldContain "e".repeat(2000)
        output shouldNotContain "e".repeat(2001)
    }

    "a 500 is HTTP_SERVER_ERROR" {
        server.handler = { TestResponse(status = 500, body = "boom", contentType = "text/plain") }
        val result = call("showItem", """{"id":"1"}""")
        result.errorType shouldBe "HTTP_SERVER_ERROR"
        result.output shouldContain "boom"
    }

    "a redirect is not followed and its Location is not echoed" {
        val location = mapOf("Location" to "http://127.0.0.1:1/evil")
        server.handler = { TestResponse(status = 302, body = "", headers = location) }
        val result = call("showItem", """{"id":"1"}""")
        result.errorType shouldBe "REDIRECT_NOT_FOLLOWED"
        result.output shouldNotContain "evil"
        server.requests.size shouldBe 1
    }

    "a timeout is TRANSPORT_ERROR" {
        val slow = ExecutionFixture.config(baseUrl = "${server.baseUrl}/api", timeoutSeconds = 1)
        val slowTools = ExecutionFixture.tools(slow, ExecutionFixture.runtime(slow, holder.client))
        server.handler = { TestResponse(body = "{}", delayMillis = 2500) }
        val result = checkNotNull(slowTools["showItem"]).executeWithJson("""{"id":"1"}""", context)
        result.errorType shouldBe "TRANSPORT_ERROR"
        result.output shouldContain "GET /api/items/1"
    }

    "a call slower than OkHttp's default read timeout succeeds within timeoutSeconds" {
        val patient = ExecutionFixture.config(baseUrl = "${server.baseUrl}/api", timeoutSeconds = 12)
        val patientTools = ExecutionFixture.tools(patient, ExecutionFixture.runtime(patient, holder.client))
        server.handler = { TestResponse(body = "{}", delayMillis = 11_000) }
        val result = checkNotNull(patientTools["showItem"]).executeWithJson("""{"id":"1"}""", context)
        result.success shouldBe true
        result.metadata["status"] shouldBe 200
    }

    "a template that leaves the base URL is URL_POLICY_REJECTED" {
        val descriptor = ExecutionFixture.descriptors(config).first { it.operationId == "showItem" }
        val escaping = OperationDescriptor(
            operationId = "escape",
            method = descriptor.method,
            pathTemplate = "/../admin/{id}",
            toolSuffix = "escape",
            description = descriptor.description,
            inputSchema = descriptor.inputSchema,
            parameters = descriptor.parameters,
            body = null,
            shaping = descriptor.shaping,
        )
        val escapingTool = HttpApiTool(escaping, ExecutionFixture.runtime(config, holder.client))
        val result = escapingTool.executeWithJson("""{"id":"1"}""", context)
        result.errorType shouldBe "URL_POLICY_REJECTED"
        server.requests.size shouldBe 0
    }

    "limits concurrent calls per integration" {
        val inFlight = AtomicInteger()
        val maxInFlight = AtomicInteger()
        server.handler = {
            maxInFlight.accumulateAndGet(inFlight.incrementAndGet(), ::maxOf)
            Thread.sleep(300)
            inFlight.decrementAndGet()
            ok("{}")
        }
        val serial = ExecutionFixture.config(baseUrl = "${server.baseUrl}/api", maxConcurrentCalls = 1)
        val serialRuntime = ExecutionFixture.runtime(serial, holder.client)
        val serialTool = checkNotNull(ExecutionFixture.tools(serial, serialRuntime)["showItem"])
        val results = coroutineScope {
            (1..3).map { async { serialTool.executeWithJson("""{"id":"$it"}""", context) } }.awaitAll()
        }
        results.all { it.success } shouldBe true
        maxInFlight.get() shouldBe 1
    }

    "waiting too long for a permit is TRANSPORT_ERROR without any request" {
        server.handler = { ok("{}") }
        val serial = ExecutionFixture.config(
            baseUrl = "${server.baseUrl}/api",
            maxConcurrentCalls = 1,
            timeoutSeconds = 1,
        )
        val runtime = ExecutionFixture.runtime(serial, holder.client)
        val serialTool = checkNotNull(ExecutionFixture.tools(serial, runtime)["showItem"])
        runtime.semaphore.acquire()
        try {
            val result = serialTool.executeWithJson("""{"id":"1"}""", context)
            result.errorType shouldBe "TRANSPORT_ERROR"
            result.output shouldContain "too many concurrent calls"
            server.requests.size shouldBe 0
        } finally {
            runtime.semaphore.release()
        }
        serialTool.executeWithJson("""{"id":"2"}""", context).success shouldBe true
        server.requests.size shouldBe 1
    }

    "a permit granted as the wait for it times out is used, never leaked" {
        server.handler = { ok("{}") }
        val serial = ExecutionFixture.config(
            baseUrl = "${server.baseUrl}/api",
            maxConcurrentCalls = 1,
            timeoutSeconds = 1,
        )
        val late = LateSemaphore(Semaphore(1), grantAfterMillis = 1300)
        val runtime = ExecutionFixture.runtime(serial, holder.client, semaphore = late)
        val serialTool = checkNotNull(ExecutionFixture.tools(serial, runtime)["showItem"])
        serialTool.executeWithJson("""{"id":"1"}""", context).success shouldBe true
        late.availablePermits shouldBe 1
        server.requests.size shouldBe 1
    }

    "cancelling the run cancels the call promptly and releases the permit" {
        server.handler = { TestResponse(body = "{}", delayMillis = 3000) }
        val serial = ExecutionFixture.config(baseUrl = "${server.baseUrl}/api", maxConcurrentCalls = 1)
        val runtime = ExecutionFixture.runtime(serial, holder.client)
        val serialTool = checkNotNull(ExecutionFixture.tools(serial, runtime)["showItem"])
        val cancelled = coroutineScope {
            val job = launch { serialTool.executeWithJson("""{"id":"1"}""", context) }
            delay(300)
            val started = System.nanoTime()
            job.cancelAndJoin()
            (System.nanoTime() - started) / 1_000_000
        }
        cancelled shouldBeLessThan 1000
        runtime.semaphore.availablePermits shouldBe 1
    }

    "a 200 with an empty body reads No content" {
        server.handler = { TestResponse(status = 200, body = "", contentType = null) }
        call("showItem", """{"id":"1"}""").output shouldBe "No content"
        call("showItem", """{"id":"1"}""").metadata["contentType"].shouldBeNull()
    }

    "output starts with the shaped body for a plain 200" {
        server.handler = { ok("""{"a":1}""") }
        call("nestedOp", """{"a":"1","b":"2"}""").output shouldStartWith "{\"a\":1}"
    }
})
