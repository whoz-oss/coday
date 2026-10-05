package io.whozoss.agentos.plugins.factorybridge

import com.fasterxml.jackson.module.kotlin.jacksonObjectMapper
import io.kotest.core.spec.style.StringSpec
import io.kotest.matchers.shouldBe
import io.kotest.matchers.string.shouldContain
import io.kotest.matchers.string.shouldNotContain
import io.whozoss.agentos.plugins.factorybridge.tools.FactoryAskStepQuestionTool
import io.whozoss.agentos.sdk.caseEvent.CaseStatusEvent
import io.whozoss.agentos.sdk.caseFlow.CaseStatus
import io.whozoss.agentos.sdk.entity.EntityMetadata
import io.whozoss.agentos.sdk.tool.ToolContext
import okhttp3.OkHttpClient
import com.sun.net.httpserver.HttpServer
import java.net.InetSocketAddress
import java.time.Instant
import java.util.UUID

/** Legacy compatibility scenarios. The tool is deliberately not exposed by FactoryWorkerToolPlugin. */
@Suppress("DEPRECATION")
class FactoryAskStepQuestionToolSpec : StringSpec({
    "legacy tool retains its wire identity for already persisted compatibility state" {
        val tool =
            FactoryAskStepQuestionTool(
                "http://127.0.0.1:8141",
                OkHttpClient(),
                jacksonObjectMapper(),
                FactoryStepResultBindingRegistry(),
            )
        tool.name shouldBe "FACTORY_WORKER__ask_step_question"
        tool.version shouldBe "1.0.0"
        tool.description shouldContain "human question"
    }

    "input schema carries no identity fields — attempt identity is runtime-injected" {
        val tool =
            FactoryAskStepQuestionTool(
                "http://127.0.0.1:8141",
                OkHttpClient(),
                jacksonObjectMapper(),
                FactoryStepResultBindingRegistry(),
            )
        tool.inputSchema shouldNotContain "attemptId"
        tool.inputSchema shouldNotContain "caseId"
        tool.inputSchema shouldNotContain "namespaceId"
        tool.inputSchema shouldNotContain "workflowId"
        tool.inputSchema shouldNotContain "stepId"
        tool.inputSchema shouldContain "\"prompt\""
        tool.inputSchema shouldContain "SINGLE_CHOICE"
    }

    "tool is fail-closed outside a Factory-bound case" {
        val tool =
            FactoryAskStepQuestionTool(
                "http://127.0.0.1:8141",
                OkHttpClient(),
                jacksonObjectMapper(),
                FactoryStepResultBindingRegistry(),
            )
        val result = tool.execute(FactoryAskStepQuestionTool.Input("Proceed?"), ToolContext(UUID.randomUUID(), null, null, emptyList(), "Worker"))
        result.success shouldBe false
        result.errorType shouldBe "FACTORY_WORKER_BINDING_INVALID"
    }

    "successful ask mirrors exactly one deterministic QuestionEvent" {
        val server = HttpServer.create(InetSocketAddress("127.0.0.1", 0), 0)
        server.createContext("/") { exchange ->
            exchange.requestBody.close()
            val bytes = """{"data":{"attemptId":"attempt","interactionId":"interaction-1","status":"waiting_human","idempotent":false,"revision":2,"workflowId":"workflow-1"}}""".toByteArray()
            exchange.sendResponseHeaders(202, bytes.size.toLong())
            exchange.responseBody.use { it.write(bytes) }
        }
        server.start()
        try {
            val namespaceId = UUID.randomUUID()
            val caseId = UUID.randomUUID()
            val registry = FactoryStepResultBindingRegistry()
            registry.bind(FactoryStepResultBinding(caseId, namespaceId, "Worker", "attempt", "runtime", "secret-token-value-with-sufficient-length", Instant.now().plusSeconds(60)))
            val mirrored = mutableListOf<io.whozoss.agentos.sdk.caseEvent.CaseEvent>()
            val tool = FactoryAskStepQuestionTool("http://127.0.0.1:${server.address.port}", OkHttpClient(), jacksonObjectMapper(), registry)
            val context = ToolContext(namespaceId, UUID.randomUUID(), "actor", listOf(CaseStatusEvent(EntityMetadata(), namespaceId, caseId, status = CaseStatus.RUNNING)), "Worker", emitEvent = mirrored::add)

            val result = tool.execute(FactoryAskStepQuestionTool.Input("Proceed?"), context)

            result.success shouldBe true
            mirrored.filterIsInstance<io.whozoss.agentos.sdk.caseEvent.QuestionEvent>().size shouldBe 1
            mirrored.filterIsInstance<io.whozoss.agentos.sdk.caseEvent.QuestionEvent>().single().id shouldBe
                UUID.nameUUIDFromBytes("factory-question|interaction-1".toByteArray())
        } finally {
            server.stop(0)
        }
    }

    "idempotent Factory replay repairs a missing QuestionEvent mirror" {
        val server = HttpServer.create(InetSocketAddress("127.0.0.1", 0), 0)
        server.createContext("/") { exchange ->
            exchange.requestBody.close()
            val bytes = """{"data":{"attemptId":"attempt","interactionId":"interaction-replay","status":"waiting_human","idempotent":true,"revision":2,"workflowId":"workflow-1"}}""".toByteArray()
            exchange.sendResponseHeaders(202, bytes.size.toLong())
            exchange.responseBody.use { it.write(bytes) }
        }
        server.start()
        try {
            val namespaceId = UUID.randomUUID()
            val caseId = UUID.randomUUID()
            val registry = FactoryStepResultBindingRegistry()
            registry.bind(FactoryStepResultBinding(caseId, namespaceId, "Worker", "attempt", "runtime", "secret-token-value-with-sufficient-length", Instant.now().plusSeconds(60)))
            val mirrored = mutableListOf<io.whozoss.agentos.sdk.caseEvent.CaseEvent>()
            val tool = FactoryAskStepQuestionTool("http://127.0.0.1:${server.address.port}", OkHttpClient(), jacksonObjectMapper(), registry)
            val context = ToolContext(namespaceId, UUID.randomUUID(), "actor", listOf(CaseStatusEvent(EntityMetadata(), namespaceId, caseId, status = CaseStatus.RUNNING)), "Worker", emitEvent = mirrored::add)

            tool.execute(FactoryAskStepQuestionTool.Input("Proceed?"), context).success shouldBe true

            mirrored.filterIsInstance<io.whozoss.agentos.sdk.caseEvent.QuestionEvent>().size shouldBe 1
        } finally {
            server.stop(0)
        }
    }

    "tool is fail-closed when the case exists but has no active binding" {
        val namespaceId = UUID.randomUUID()
        val caseId = UUID.randomUUID()
        val context =
            ToolContext(
                namespaceId,
                UUID.randomUUID(),
                "actor",
                listOf(CaseStatusEvent(metadata = EntityMetadata(), namespaceId = namespaceId, caseId = caseId, status = CaseStatus.RUNNING)),
                "Worker",
            )
        val tool =
            FactoryAskStepQuestionTool(
                "http://127.0.0.1:8141",
                OkHttpClient(),
                jacksonObjectMapper(),
                FactoryStepResultBindingRegistry(),
            )
        val result = tool.execute(FactoryAskStepQuestionTool.Input("Proceed?"), context)
        result.success shouldBe false
        result.errorType shouldBe "FACTORY_WORKER_BINDING_MISSING"
    }
})
