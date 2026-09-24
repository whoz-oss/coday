package io.whozoss.agentos.plugins.factorybridge

import com.fasterxml.jackson.module.kotlin.jacksonObjectMapper
import com.sun.net.httpserver.HttpServer
import io.kotest.assertions.throwables.shouldThrow
import io.kotest.core.spec.style.StringSpec
import io.kotest.matchers.shouldBe
import io.whozoss.agentos.plugins.factorybridge.tools.FactoryRequestHumanDecisionTool
import io.whozoss.agentos.sdk.caseEvent.CaseStatusEvent
import io.whozoss.agentos.sdk.caseEvent.QuestionType
import io.whozoss.agentos.sdk.caseFlow.CaseStatus
import io.whozoss.agentos.sdk.entity.EntityMetadata
import io.whozoss.agentos.sdk.tool.ToolContext
import okhttp3.OkHttpClient
import java.net.InetSocketAddress
import java.util.UUID

private class Suspended : RuntimeException("suspended")

class FactoryRequestHumanDecisionToolSpec : StringSpec({
    val mapper = jacksonObjectMapper()

    fun context(): ToolContext {
        val namespaceId = UUID.randomUUID()
        return ToolContext(
            namespaceId,
            UUID.randomUUID(),
            "external-user",
            listOf(CaseStatusEvent(EntityMetadata(), namespaceId, UUID.randomUUID(), status = CaseStatus.PENDING)),
            "ProductEngineer",
        )
    }

    val input =
        FactoryRequestHumanDecisionTool.Input(
            "wf-1",
            "intent-checkpoint",
            3,
            "Approve the intent?",
            listOf(
                FactoryRequestHumanDecisionTool.Action("approve", "Approve", "completed"),
                FactoryRequestHumanDecisionTool.Action("reject", "Reject", "failed"),
            ),
            "human-1",
        )

    "strict schema exposes no trust attribution" {
        val schema =
            mapper.readTree(
                FactoryRequestHumanDecisionTool("http://localhost", OkHttpClient(), mapper, "runtime")
                    .inputSchema,
            )
        schema.path("properties").fieldNames().asSequence().toSet() shouldBe
            setOf("workflowId", "stepId", "expectedRevision", "prompt", "actions", "idempotencyKey")
        schema.path("additionalProperties").asBoolean() shouldBe false
    }

    "sends exact route payload and trusted headers then suspends with the server checkpoint" {
        var path = ""
        var body = ""
        var headers = emptyMap<String, String>()
        val server = HttpServer.create(InetSocketAddress("127.0.0.1", 0), 0)
        server.createContext("/") { exchange ->
            path = exchange.requestURI.toString()
            body = exchange.requestBody.bufferedReader().readText()
            headers =
                listOf("x-factory-namespace-id", "x-factory-runtime-id", "x-factory-agent-id", "x-factory-case-id")
                    .associateWith { exchange.requestHeaders.getFirst(it) }
            val bytes = """{"data":{"interaction":{"interactionId":"gate-1","revision":4}}}""".toByteArray()
            exchange.sendResponseHeaders(200, bytes.size.toLong())
            exchange.responseBody.use { it.write(bytes) }
        }
        server.start()
        try {
            var awaited: FactoryAwaitAnswer? = null
            val tool =
                FactoryRequestHumanDecisionTool(
                    "http://127.0.0.1:${server.address.port}",
                    OkHttpClient(),
                    mapper,
                    "runtime-configured",
                ) { await ->
                    awaited = await
                    throw Suspended()
                }
            val toolContext = context()
            shouldThrow<Suspended> { tool.execute(input, toolContext) }

            path shouldBe "/api/factory/workflows/wf-1/interactions"
            val sent = mapper.readTree(body)
            sent.path("stepId").asText() shouldBe "intent-checkpoint"
            sent.path("expectedRevision").asLong() shouldBe 3L
            sent.path("kind").asText() shouldBe "approval"
            sent.path("idempotencyKey").asText() shouldBe "human-1"
            headers["x-factory-namespace-id"] shouldBe toolContext.namespaceId.toString()
            headers["x-factory-runtime-id"] shouldBe "runtime-configured"
            headers["x-factory-agent-id"] shouldBe "ProductEngineer"
            headers["x-factory-case-id"] shouldBe toolContext.caseEvents.single().caseId.toString()

            val await = awaited!!
            await.question shouldBe "Approve the intent?"
            await.options shouldBe listOf("Approve", "Reject")
            await.questionType shouldBe QuestionType.SINGLE_CHOICE
            await.userId shouldBe toolContext.userId
            await.factoryCheckpoint.workflowId shouldBe "wf-1"
            await.factoryCheckpoint.interactionId shouldBe "gate-1"
            await.factoryCheckpoint.interactionRevision shouldBe 4L
        } finally {
            server.stop(0)
        }
    }

    "malformed response missing interaction id does not suspend" {
        val server = HttpServer.create(InetSocketAddress("127.0.0.1", 0), 0)
        server.createContext("/") { exchange ->
            exchange.requestBody.close()
            val bytes = """{"data":{"interaction":{"interactionId":""}}}""".toByteArray()
            exchange.sendResponseHeaders(200, bytes.size.toLong())
            exchange.responseBody.use { it.write(bytes) }
        }
        server.start()
        try {
            val tool =
                FactoryRequestHumanDecisionTool(
                    "http://127.0.0.1:${server.address.port}",
                    OkHttpClient(),
                    mapper,
                    "runtime",
                ) { throw Suspended() }
            val result = tool.execute(input, context())
            result.success shouldBe false
            result.errorType shouldBe "MALFORMED_FACTORY_RESPONSE"
        } finally {
            server.stop(0)
        }
    }

    "rejects malformed action sets before any HTTP call" {
        val tool = FactoryRequestHumanDecisionTool("http://127.0.0.1:1", OkHttpClient(), mapper, "runtime") { throw Suspended() }
        val result = tool.execute(input.copy(actions = emptyList()), context())
        result.errorType shouldBe "INVALID_INTERACTION"
    }
})
