package io.whozoss.agentos.plugins.factorybridge

import com.fasterxml.jackson.module.kotlin.jacksonObjectMapper
import com.sun.net.httpserver.HttpServer
import io.kotest.core.spec.style.StringSpec
import io.kotest.matchers.shouldBe
import io.whozoss.agentos.plugins.factorybridge.tools.FactoryGetRequiredHumanActionsTool
import io.whozoss.agentos.sdk.tool.ToolContext
import okhttp3.OkHttpClient
import java.net.InetSocketAddress
import java.util.UUID

class FactoryGetRequiredHumanActionsToolSpec : StringSpec({
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
        """{"data":{"allowedActions":[
            {"type":"reply","interactionId":"int-1","stepId":"gate","questionEventId":"q-1","expectedRevision":9,"label":"Approve the gate?"},
            {"type":"retry","stepId":"build","expectedRevision":4},
            {"type":"cancel_attempt","attemptId":"attempt-1","stepId":"build","caseId":"case-1","expectedRevision":5}
        ],"blockers":[]}}"""

    "schema exposes workflowId only with additionalProperties false" {
        val tool = FactoryGetRequiredHumanActionsTool("http://localhost", OkHttpClient(), mapper)
        val schema = mapper.readTree(tool.inputSchema)
        schema.path("properties").fieldNames().asSequence().toList() shouldBe listOf("workflowId")
        schema.path("required").map { it.asText() } shouldBe listOf("workflowId")
        schema.path("additionalProperties").asBoolean() shouldBe false
    }

    "only reply actions are returned with the contract shape" {
        var requestedPath: String? = null
        val (server, baseUrl) = serve(200, actionsBody) { requestedPath = it }
        try {
            val namespace = UUID.randomUUID()
            val tool = FactoryGetRequiredHumanActionsTool(baseUrl, OkHttpClient(), mapper)
            val result = tool.execute(FactoryGetRequiredHumanActionsTool.Input("wf-1"), ToolContext(namespace, null, null, emptyList(), "agent"))
            result.success shouldBe true
            requestedPath shouldBe "/api/factory/workflows/wf-1/actions?namespaceId=$namespace"
            val actions = mapper.readTree(result.output)
            actions.isArray shouldBe true
            actions.size() shouldBe 1
            val first = actions[0]
            first.fieldNames().asSequence().toSet() shouldBe setOf(
                "interactionId", "stepId", "questionEventId", "prompt", "actions", "expectedRevision",
            )
            first.path("interactionId").asText() shouldBe "int-1"
            first.path("stepId").asText() shouldBe "gate"
            first.path("questionEventId").asText() shouldBe "q-1"
            first.path("prompt").asText() shouldBe "Approve the gate?"
            first.path("expectedRevision").asInt() shouldBe 9
            first.path("actions").map { it.path("id").asText() } shouldBe listOf("approve", "reject")
        } finally {
            server.stop(0)
        }
    }

    "a caller without authorized reply actions gets an empty array" {
        val (server, baseUrl) = serve(200, """{"data":{"allowedActions":[{"type":"retry","stepId":"build","expectedRevision":4}],"blockers":[]}}""")
        try {
            val tool = FactoryGetRequiredHumanActionsTool(baseUrl, OkHttpClient(), mapper)
            val result =
                tool.execute(
                    FactoryGetRequiredHumanActionsTool.Input("wf-1"),
                    ToolContext(UUID.randomUUID(), null, null, emptyList(), "agent"),
                )
            result.success shouldBe true
            mapper.readTree(result.output).size() shouldBe 0
        } finally {
            server.stop(0)
        }
    }

    "rejects an invalid workflow identifier before any network call" {
        val tool = FactoryGetRequiredHumanActionsTool("http://localhost", OkHttpClient(), mapper)
        val context = ToolContext(UUID.randomUUID(), null, null, emptyList(), "agent")
        tool.execute(FactoryGetRequiredHumanActionsTool.Input("bad id"), context).errorType shouldBe "INVALID_WORKFLOW_ID"
        tool.execute(FactoryGetRequiredHumanActionsTool.Input(""), context).errorType shouldBe "INVALID_WORKFLOW_ID"
        tool.execute(null, context).errorType shouldBe "INVALID_WORKFLOW_ID"
    }

    "Factory errors and malformed bodies map to the stable codes" {
        val tool = FactoryGetRequiredHumanActionsTool("http://localhost", OkHttpClient(), mapper)
        tool.parseResponse(404, """{"error":{"code":"WORKFLOW_NOT_FOUND","message":"missing"}}""")
            .errorType shouldBe "WORKFLOW_NOT_FOUND"
        tool.parseResponse(401, """{"error":{"code":"TRUST_CONTEXT_UNAVAILABLE","message":"no trust"}}""")
            .errorType shouldBe "TRUST_CONTEXT_UNAVAILABLE"
        tool.parseResponse(500, "boom").errorType shouldBe "FACTORY_REQUEST_FAILED"
        tool.parseResponse(200, "not-json").errorType shouldBe "MALFORMED_FACTORY_RESPONSE"
        tool.parseResponse(200, """{"data":{"blockers":[]}}""").errorType shouldBe "MALFORMED_FACTORY_RESPONSE"
    }
})
