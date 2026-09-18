package io.whozoss.agentos.factory

import com.fasterxml.jackson.module.kotlin.jacksonObjectMapper
import com.sun.net.httpserver.HttpServer
import io.kotest.core.spec.style.StringSpec
import io.kotest.matchers.shouldBe
import io.whozoss.agentos.sdk.caseEvent.CaseStatusEvent
import io.whozoss.agentos.sdk.caseFlow.CaseStatus
import io.whozoss.agentos.sdk.entity.EntityMetadata
import io.whozoss.agentos.sdk.tool.ToolContext
import io.whozoss.agentos.sdk.tool.ToolExecutionResult
import okhttp3.OkHttpClient
import java.net.InetSocketAddress
import java.util.UUID

class FactoryRequestTransitionToolSpec : StringSpec({
    val mapper = jacksonObjectMapper()
    val input = FactoryRequestTransitionTool.Input(
        workflowId = "wf-1",
        stepId = "build",
        expectedRevision = 4,
        requestedStatus = "completed",
        evidenceIds = listOf("evidence-1"),
        idempotencyKey = "transition-1",
    )

    fun context(): ToolContext {
        val namespaceId = UUID.randomUUID()
        val caseId = UUID.randomUUID()
        return ToolContext(
            namespaceId,
            UUID.randomUUID(),
            "external-actor",
            listOf(
                CaseStatusEvent(
                    metadata = EntityMetadata(),
                    namespaceId = namespaceId,
                    caseId = caseId,
                    status = CaseStatus.PENDING,
                ),
            ),
            "Builder",
        )
    }

    "strict schema excludes trust attribution and requestId" {
        val tool = FactoryRequestTransitionTool("http://localhost", OkHttpClient(), mapper, "runtime")
        val schema = mapper.readTree(tool.inputSchema)
        schema.path("properties").fieldNames().asSequence().toSet() shouldBe setOf(
            "workflowId", "stepId", "expectedRevision", "requestedStatus", "evidenceIds", "idempotencyKey",
        )
        schema.path("additionalProperties").asBoolean() shouldBe false
        schema.path("properties").has("requestId") shouldBe false
        schema.path("properties").has("namespaceId") shouldBe false
        schema.path("properties").has("agentId") shouldBe false
    }

    "sends exact URL payload and injected execution" {
        var requestedPath = ""
        var requestedBody = ""
        val server = HttpServer.create(InetSocketAddress("127.0.0.1", 0), 0)
        server.createContext("/") { exchange ->
            requestedPath = exchange.requestURI.toString()
            requestedBody = exchange.requestBody.bufferedReader().readText()
            val bytes = """{"data":{"workflowId":"wf-1","requestId":"factory-id","revision":5,"changed":true,"idempotent":false,"projection":{}}}""".toByteArray()
            exchange.sendResponseHeaders(200, bytes.size.toLong())
            exchange.responseBody.use { it.write(bytes) }
        }
        server.start()
        try {
            val toolContext = context()
            val result = FactoryRequestTransitionTool(
                "http://127.0.0.1:${server.address.port}", OkHttpClient(), mapper, "runtime-configured",
            ).execute(input, toolContext)
            result.success shouldBe true
            requestedPath shouldBe "/api/factory/workflows/wf-1/transitions"
            val sent = mapper.readTree(requestedBody)
            sent.path("transition").fieldNames().asSequence().toSet() shouldBe setOf(
                "workflowId", "stepId", "expectedRevision", "requestedStatus", "evidenceIds", "idempotencyKey",
            )
            sent.path("execution").path("namespaceId").asText() shouldBe toolContext.namespaceId.toString()
            sent.path("execution").path("runtimeId").asText() shouldBe "runtime-configured"
            sent.path("execution").path("agentId").asText() shouldBe "Builder"
            sent.path("execution").path("actorId").asText() shouldBe "external-actor"
            sent.path("execution").path("caseId").asText() shouldBe toolContext.caseEvents.single().caseId.toString()
        } finally {
            server.stop(0)
        }
    }

    "propagates idempotent policy rejection and malformed response" {
        suspend fun call(status: Int, response: String): ToolExecutionResult {
            val server = HttpServer.create(InetSocketAddress("127.0.0.1", 0), 0)
            server.createContext("/") { exchange ->
                exchange.requestBody.close()
                val bytes = response.toByteArray()
                exchange.sendResponseHeaders(status, bytes.size.toLong())
                exchange.responseBody.use { it.write(bytes) }
            }
            server.start()
            return try {
                FactoryRequestTransitionTool(
                    "http://127.0.0.1:${server.address.port}", OkHttpClient(), mapper, "runtime",
                ).execute(input, context())
            } finally {
                server.stop(0)
            }
        }

        call(200, """{"data":{"revision":4,"changed":false,"idempotent":true}}""").success shouldBe true
        call(409, """{"error":{"code":"PASS_EVIDENCE_REQUIRED","message":"matching pass required"}}""").errorType shouldBe "PASS_EVIDENCE_REQUIRED"
        call(200, "bad").errorType shouldBe "MALFORMED_FACTORY_RESPONSE"
    }

    "grant is exact close capability refused and absent grant grants nothing" {
        val grant = FactoryToolGrantService(FactoryToolPlugin(mapper, "http://localhost", "runtime"))
        val toolContext = ToolContext(UUID.randomUUID(), null, null, emptyList())
        grant.grantTools(toolContext, mapOf("FACTORY" to listOf("request_transition"))).map { it.name } shouldBe
            listOf("FACTORY__request_transition")
        grant.grantTools(toolContext, mapOf("FACTORY" to listOf("request_transitions"))).isEmpty() shouldBe true
        grant.grantTools(toolContext, null).isEmpty() shouldBe true
        grant.grantTools(toolContext, mapOf("FACTORY" to emptyList())).isEmpty() shouldBe true
    }
})
