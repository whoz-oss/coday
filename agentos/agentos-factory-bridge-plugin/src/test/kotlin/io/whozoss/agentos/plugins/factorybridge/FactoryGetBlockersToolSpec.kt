package io.whozoss.agentos.plugins.factorybridge

import com.fasterxml.jackson.module.kotlin.jacksonObjectMapper
import com.sun.net.httpserver.HttpServer
import io.kotest.core.spec.style.StringSpec
import io.kotest.matchers.shouldBe
import io.whozoss.agentos.plugins.factorybridge.tools.FactoryGetBlockersTool
import io.whozoss.agentos.sdk.tool.ToolContext
import okhttp3.OkHttpClient
import java.net.InetSocketAddress
import java.util.UUID

class FactoryGetBlockersToolSpec : StringSpec({
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

    val actionsBody =
        """{"data":{"allowedActions":[],"blockers":[
            {"code":"WAITING_HUMAN_INTERACTION","stepId":"gate","message":"A decision is pending."},
            {"code":"VERIFICATION_FAILED","stepId":"build","message":"Oracle failed."},
            {"code":"UNKNOWN_RUNTIME","stepId":null,"message":"Runtime state is indeterminate."}
        ]}}"""

    "schema exposes workflowId only with additionalProperties false" {
        val tool = FactoryGetBlockersTool("http://localhost", OkHttpClient(), mapper)
        val schema = mapper.readTree(tool.inputSchema)
        schema.path("properties").fieldNames().asSequence().toList() shouldBe listOf("workflowId")
        schema.path("required").map { it.asText() } shouldBe listOf("workflowId")
        schema.path("additionalProperties").asBoolean() shouldBe false
    }

    "request hits the authoritative actions endpoint and returns the Factory blockers verbatim" {
        var requestedPath: String? = null
        val (server, baseUrl) = serve(200, actionsBody) { requestedPath = it }
        try {
            val namespace = UUID.randomUUID()
            val tool = FactoryGetBlockersTool(baseUrl, OkHttpClient(), mapper)
            val result = tool.execute(FactoryGetBlockersTool.Input("wf-1"), ToolContext(namespace, null, null, emptyList(), "agent"))
            result.success shouldBe true
            requestedPath shouldBe "/api/factory/workflows/wf-1/actions?namespaceId=$namespace"
            val blockers = mapper.readTree(result.output)
            blockers.isArray shouldBe true
            blockers.size() shouldBe 3
            blockers[0].fieldNames().asSequence().toSet() shouldBe setOf("code", "stepId", "message")
            blockers[0].path("code").asText() shouldBe "WAITING_HUMAN_INTERACTION"
            blockers[0].path("stepId").asText() shouldBe "gate"
            blockers[1].path("code").asText() shouldBe "VERIFICATION_FAILED"
            blockers[2].path("stepId").isNull shouldBe true
            result.metadata["count"] shouldBe 3
        } finally {
            server.stop(0)
        }
    }

    "no blocker degrades to an empty array" {
        val (server, baseUrl) = serve(200, """{"data":{"allowedActions":[],"blockers":[]}}""")
        try {
            val tool = FactoryGetBlockersTool(baseUrl, OkHttpClient(), mapper)
            val result =
                tool.execute(FactoryGetBlockersTool.Input("wf-1"), ToolContext(UUID.randomUUID(), null, null, emptyList(), "agent"))
            result.success shouldBe true
            mapper.readTree(result.output).size() shouldBe 0
        } finally {
            server.stop(0)
        }
    }

    "rejects an invalid workflow identifier before any network call" {
        val tool = FactoryGetBlockersTool("http://localhost", OkHttpClient(), mapper)
        val context = ToolContext(UUID.randomUUID(), null, null, emptyList(), "agent")
        tool.execute(FactoryGetBlockersTool.Input("bad id"), context).errorType shouldBe "INVALID_WORKFLOW_ID"
        tool.execute(FactoryGetBlockersTool.Input("x".repeat(129)), context).errorType shouldBe "INVALID_WORKFLOW_ID"
        tool.execute(null, context).errorType shouldBe "INVALID_WORKFLOW_ID"
    }

    "Factory errors and malformed bodies map to the stable codes" {
        val tool = FactoryGetBlockersTool("http://localhost", OkHttpClient(), mapper)
        tool.parseResponse(404, """{"error":{"code":"WORKFLOW_NOT_FOUND","message":"missing"}}""")
            .errorType shouldBe "WORKFLOW_NOT_FOUND"
        tool.parseResponse(403, """{"error":{"code":"WORKSTREAM_BOUNDARY_VIOLATION","message":"outside"}}""")
            .errorType shouldBe "WORKSTREAM_BOUNDARY_VIOLATION"
        tool.parseResponse(500, "boom").errorType shouldBe "FACTORY_REQUEST_FAILED"
        tool.parseResponse(200, "not-json").errorType shouldBe "MALFORMED_FACTORY_RESPONSE"
        tool.parseResponse(200, """{"data":{"allowedActions":[]}}""").errorType shouldBe "MALFORMED_FACTORY_RESPONSE"
    }
})
