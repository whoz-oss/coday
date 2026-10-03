package io.whozoss.agentos.plugins.factorybridge

import com.fasterxml.jackson.module.kotlin.jacksonObjectMapper
import com.sun.net.httpserver.HttpServer
import io.kotest.matchers.shouldBe
import io.kotest.core.spec.style.StringSpec
import io.whozoss.agentos.plugins.factorybridge.tools.FactoryGetWorkstreamTool
import io.whozoss.agentos.sdk.tool.ToolContext
import io.whozoss.agentos.sdk.tool.ToolExecutionResult
import okhttp3.OkHttpClient
import java.net.InetSocketAddress
import java.util.UUID

class FactoryGetWorkstreamToolSpec : StringSpec({
    val mapper = jacksonObjectMapper()

    fun serve(
        status: Int,
        body: String,
        onRequest: (String) -> Unit = {},
    ): Pair<HttpServer, String> {
        val server = HttpServer.create(InetSocketAddress("127.0.0.1", 0), 0)
        server.createContext("/") { exchange ->
            onRequest(exchange.requestURI.toString())
            val bytes = body.toByteArray()
            exchange.sendResponseHeaders(status, bytes.size.toLong())
            exchange.responseBody.use { it.write(bytes) }
        }
        server.start()
        return server to "http://127.0.0.1:${server.address.port}"
    }

    val projectionBody =
        """{
            "workstreamId":"ws-1","namespaceId":"ns-1","status":"active","workstreamRevision":"12",
            "activeWorkflows":{"count":2,"truncated":false,"items":[{"workflowId":"wf-1","namespaceId":"ns-1","workflowType":"delivery","title":"W","status":"running","revision":7}]},
            "steps":{"running":1,"waitingHuman":1,"blocked":2,"items":[],"truncated":false},
            "attempts":{"count":1,"items":[],"truncated":false},
            "humanActions":{"count":1,"truncated":false,"items":[{"interactionId":"int-1","workflowId":"wf-1","stepId":"gate","interactionType":"checkpoint","status":"open"}]},
            "failedOracles":{"count":3,"items":[],"truncated":true},
            "environments":{"count":0,"byState":{},"items":[],"truncated":false},
            "recentChanges":{"count":0,"items":[],"truncated":false},
            "boundaryViolations":1
        }"""

    "schema exposes workstreamId only with additionalProperties false" {
        val tool = FactoryGetWorkstreamTool("http://localhost", OkHttpClient(), mapper)
        val schema = mapper.readTree(tool.inputSchema)
        schema.path("properties").fieldNames().asSequence().toList() shouldBe listOf("workstreamId")
        schema.path("required").map { it.asText() } shouldBe listOf("workstreamId")
        schema.path("additionalProperties").asBoolean() shouldBe false
        schema.path("properties").path("workstreamId").path("maxLength").asInt() shouldBe 128
    }

    "request injects namespace from ToolContext and maps the bounded projection" {
        var requestedPath: String? = null
        val (server, baseUrl) = serve(200, projectionBody) { requestedPath = it }
        try {
            val namespace = UUID.randomUUID()
            val tool = FactoryGetWorkstreamTool(baseUrl, OkHttpClient(), mapper)
            val result = tool.execute(FactoryGetWorkstreamTool.Input("ws-1"), ToolContext(namespace, null, null, emptyList(), "agent"))
            result.success shouldBe true
            requestedPath shouldBe "/api/factory/workstreams/ws-1/projection?namespaceId=$namespace"
            val output = mapper.readTree(result.output)
            output.path("workstreamId").asText() shouldBe "ws-1"
            output.path("status").asText() shouldBe "active"
            output.path("workstreamRevision").asText() shouldBe "12"
            output.path("activeWorkflows").path("count").asInt() shouldBe 2
            output.path("activeWorkflows").path("items").size() shouldBe 1
            output.path("steps").path("running").asInt() shouldBe 1
            output.path("steps").path("waitingHuman").asInt() shouldBe 1
            output.path("steps").path("blocked").asInt() shouldBe 2
            output.path("pendingHumanActions").path("count").asInt() shouldBe 1
            output.path("pendingHumanActions").path("items").size() shouldBe 1
            val blockers = output.path("mainBlockers")
            blockers.path("blockedSteps").asInt() shouldBe 2
            blockers.path("waitingHumanSteps").asInt() shouldBe 1
            blockers.path("pendingHumanActions").asInt() shouldBe 1
            blockers.path("failedOracles").asInt() shouldBe 3
            blockers.path("boundaryViolations").asInt() shouldBe 1
            result.metadata["workstreamRevision"] shouldBe "12"
        } finally {
            server.stop(0)
        }
    }

    "rejects an invalid workstream identifier before any network call" {
        val tool = FactoryGetWorkstreamTool("http://localhost", OkHttpClient(), mapper)
        val context = ToolContext(UUID.randomUUID(), null, null, emptyList(), "agent")
        tool.execute(FactoryGetWorkstreamTool.Input(""), context).errorType shouldBe "INVALID_WORKSTREAM_SLUG"
        tool.execute(FactoryGetWorkstreamTool.Input("ws/with/slashes"), context).errorType shouldBe "INVALID_WORKSTREAM_SLUG"
        tool.execute(FactoryGetWorkstreamTool.Input("x".repeat(129)), context).errorType shouldBe "INVALID_WORKSTREAM_SLUG"
        tool.execute(null, context).errorType shouldBe "INVALID_WORKSTREAM_SLUG"
    }

    "surfaces a Factory boundary violation with the exact Factory code" {
        suspend fun call(status: Int, body: String): ToolExecutionResult {
            val (server, baseUrl) = serve(status, body)
            return try {
                FactoryGetWorkstreamTool(baseUrl, OkHttpClient(), mapper)
                    .execute(FactoryGetWorkstreamTool.Input("ws-1"), ToolContext(UUID.randomUUID(), null, null, emptyList(), "agent"))
            } finally {
                server.stop(0)
            }
        }
        call(403, """{"error":{"code":"WORKSTREAM_BOUNDARY_VIOLATION","message":"outside the trusted scope"}}""")
            .errorType shouldBe "WORKSTREAM_BOUNDARY_VIOLATION"
        call(404, """{"error":{"code":"WORKSTREAM_NOT_FOUND","message":"missing"}}""").errorType shouldBe "WORKSTREAM_NOT_FOUND"
        call(401, """{"error":{"code":"TRUST_CONTEXT_UNAVAILABLE","message":"no trust"}}""").errorType shouldBe "TRUST_CONTEXT_UNAVAILABLE"
        call(500, """{"error":{}}""").errorType shouldBe "FACTORY_REQUEST_FAILED"
        call(200, "not-json").errorType shouldBe "MALFORMED_FACTORY_RESPONSE"
        call(200, """{"namespaceId":"ns-1"}""").errorType shouldBe "MALFORMED_FACTORY_RESPONSE"
    }

    "connection failure maps to FACTORY_UNAVAILABLE" {
        val server = HttpServer.create(InetSocketAddress("127.0.0.1", 0), 0)
        server.start()
        val port = server.address.port
        server.stop(0)
        val tool = FactoryGetWorkstreamTool("http://127.0.0.1:$port", OkHttpClient(), mapper)
        tool.execute(FactoryGetWorkstreamTool.Input("ws-1"), ToolContext(UUID.randomUUID(), null, null, emptyList(), "agent"))
            .errorType shouldBe "FACTORY_UNAVAILABLE"
    }
})
