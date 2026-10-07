package io.whozoss.agentos.plugins.factorybridge

import com.fasterxml.jackson.module.kotlin.jacksonObjectMapper
import com.sun.net.httpserver.HttpServer
import io.kotest.core.spec.style.StringSpec
import io.kotest.matchers.shouldBe
import io.whozoss.agentos.plugins.factorybridge.tools.FactoryListWorkflowsTool
import io.whozoss.agentos.sdk.tool.ToolContext
import okhttp3.OkHttpClient
import java.net.InetSocketAddress
import java.util.UUID

class FactoryListWorkflowsToolSpec : StringSpec({
    val mapper = jacksonObjectMapper()

    fun serve(body: String, onRequest: (String) -> Unit = {}): Pair<HttpServer, String> {
        val server = HttpServer.create(InetSocketAddress("127.0.0.1", 0), 0)
        server.createContext("/") { exchange ->
            onRequest(exchange.requestURI.toString())
            val bytes = body.toByteArray()
            exchange.sendResponseHeaders(200, bytes.size.toLong())
            exchange.responseBody.use { it.write(bytes) }
        }
        server.start()
        return server to "http://127.0.0.1:${server.address.port}"
    }

    fun item(id: String, type: String, revision: Int): String =
        """{"workflowId":"$id","namespaceId":"ns","revision":$revision,"projection":{"workflowId":"$id","workflowType":"$type","title":"T-$id","status":"ready","steps":[]}}"""

    "schema exposes exactly the bounded filters with no required property" {
        val tool = FactoryListWorkflowsTool("http://localhost", OkHttpClient(), mapper)
        val schema = mapper.readTree(tool.inputSchema)
        schema.path("properties").fieldNames().asSequence().toSet() shouldBe setOf("state", "workflowType", "limit", "cursor")
        schema.path("additionalProperties").asBoolean() shouldBe false
        schema.path("required").size() shouldBe 0
        schema.path("properties").path("state").path("enum").map { it.asText() } shouldBe listOf("active", "removed", "purged", "all")
        schema.path("properties").path("limit").path("maximum").asInt() shouldBe 200
        schema.path("properties").path("cursor").path("maxLength").asInt() shouldBe 256
        schema.has("properties") && schema.path("properties").has("workstreamId") shouldBe false
    }

    "defaults inject namespace from ToolContext and apply the default bound" {
        var requestedPath: String? = null
        val body = """{"data":{"namespaceId":"ns","state":"active","items":[${item("wf-1", "delivery", 7)}],"truncated":false}}"""
        val (server, baseUrl) = serve(body) { requestedPath = it }
        try {
            val namespace = UUID.randomUUID()
            val tool = FactoryListWorkflowsTool(baseUrl, OkHttpClient(), mapper)
            val result = tool.execute(FactoryListWorkflowsTool.Input(), ToolContext(namespace, null, null, emptyList(), "agent"))
            result.success shouldBe true
            requestedPath shouldBe "/api/factory/workflows?namespaceId=$namespace&state=active&limit=50"
            val output = mapper.readTree(result.output)
            output.path("items").size() shouldBe 1
            val first = output.path("items")[0]
            first.path("workflowId").asText() shouldBe "wf-1"
            first.path("workflowType").asText() shouldBe "delivery"
            first.path("title").asText() shouldBe "T-wf-1"
            first.path("status").asText() shouldBe "ready"
            first.path("revision").asLong() shouldBe 7L
            first.has("projection") shouldBe false
            output.path("nextCursor").isNull shouldBe true
        } finally {
            server.stop(0)
        }
    }

    "limit is coerced into the contract bound and offset widens the server window" {
        var requestedPath: String? = null
        val body = """{"data":{"items":[${item("wf-1", "a", 1)},${item("wf-2", "a", 2)},${item("wf-3", "a", 3)}],"truncated":true}}"""
        val (server, baseUrl) = serve(body) { requestedPath = it }
        try {
            val tool = FactoryListWorkflowsTool(baseUrl, OkHttpClient(), mapper)
            val context = ToolContext(UUID.randomUUID(), null, null, emptyList(), "agent")
            val result = tool.execute(FactoryListWorkflowsTool.Input(limit = 500, cursor = "1"), context)
            result.success shouldBe true
            // limit coerced to 200, server window = offset(1) + limit(200) coerced to 200.
            requestedPath shouldBe "/api/factory/workflows?namespaceId=${context.namespaceId}&state=active&limit=200"
            val output = mapper.readTree(result.output)
            // offset 1 drops wf-1; server reports more items, so a next cursor is emitted.
            output.path("items").map { it.path("workflowId").asText() } shouldBe listOf("wf-2", "wf-3")
            output.path("nextCursor").asText() shouldBe "3"
        } finally {
            server.stop(0)
        }
    }

    "filters are passed through and purged or all read the bounded active view" {
        var requestedPath: String? = null
        val body = """{"data":{"items":[],"truncated":false}}"""
        val (server, baseUrl) = serve(body) { requestedPath = it }
        try {
            val tool = FactoryListWorkflowsTool(baseUrl, OkHttpClient(), mapper)
            val context = ToolContext(UUID.randomUUID(), null, null, emptyList(), "agent")
            tool.execute(FactoryListWorkflowsTool.Input(state = "removed", workflowType = "delivery", limit = 10), context)
            requestedPath shouldBe "/api/factory/workflows?namespaceId=${context.namespaceId}&state=removed&limit=10&workflowType=delivery"
            tool.execute(FactoryListWorkflowsTool.Input(state = "purged"), context)
            requestedPath shouldBe "/api/factory/workflows?namespaceId=${context.namespaceId}&state=active&limit=50"
            tool.execute(FactoryListWorkflowsTool.Input(state = "all"), context)
            requestedPath shouldBe "/api/factory/workflows?namespaceId=${context.namespaceId}&state=active&limit=50"
        } finally {
            server.stop(0)
        }
    }

    "nextCursor is null on the last window and set while more items remain" {
        val tool = FactoryListWorkflowsTool("http://localhost", OkHttpClient(), mapper)
        val three = """{"data":{"items":[${item("wf-1", "a", 1)},${item("wf-2", "a", 2)},${item("wf-3", "a", 3)}],"truncated":false}}"""
        mapper.readTree(tool.parseResponse(200, three, 0, 2).output).path("nextCursor").asText() shouldBe "2"
        mapper.readTree(tool.parseResponse(200, three, 1, 2).output).path("nextCursor").isNull shouldBe true
        mapper.readTree(tool.parseResponse(200, three, 3, 2).output).path("items").size() shouldBe 0
        val truncated = """{"data":{"items":[${item("wf-1", "a", 1)}],"truncated":true}}"""
        mapper.readTree(tool.parseResponse(200, truncated, 0, 1).output).path("nextCursor").asText() shouldBe "1"
    }

    "rejects invalid filters and cursors before any network call" {
        val tool = FactoryListWorkflowsTool("http://localhost", OkHttpClient(), mapper)
        val context = ToolContext(UUID.randomUUID(), null, null, emptyList(), "agent")
        tool.execute(FactoryListWorkflowsTool.Input(state = "running"), context).errorType shouldBe "INVALID_REQUEST"
        tool.execute(FactoryListWorkflowsTool.Input(cursor = "not-a-number"), context).errorType shouldBe "INVALID_REQUEST"
        tool.execute(FactoryListWorkflowsTool.Input(cursor = "-1"), context).errorType shouldBe "INVALID_REQUEST"
        tool.execute(FactoryListWorkflowsTool.Input(workflowType = "bad type"), context).errorType shouldBe "INVALID_REQUEST"
    }

    "Factory errors and malformed bodies map to the stable codes" {
        val tool = FactoryListWorkflowsTool("http://localhost", OkHttpClient(), mapper)
        tool.parseResponse(400, """{"error":{"code":"UNSUPPORTED_STATE","message":"bad state"}}""", 0, 50)
            .errorType shouldBe "UNSUPPORTED_STATE"
        tool.parseResponse(401, """{"error":{"code":"TRUST_CONTEXT_UNAVAILABLE","message":"no trust"}}""", 0, 50)
            .errorType shouldBe "TRUST_CONTEXT_UNAVAILABLE"
        tool.parseResponse(500, "boom", 0, 50).errorType shouldBe "FACTORY_REQUEST_FAILED"
        tool.parseResponse(200, "not-json", 0, 50).errorType shouldBe "MALFORMED_FACTORY_RESPONSE"
        tool.parseResponse(200, """{"data":{"items":{}}}""", 0, 50).errorType shouldBe "MALFORMED_FACTORY_RESPONSE"
    }
})
