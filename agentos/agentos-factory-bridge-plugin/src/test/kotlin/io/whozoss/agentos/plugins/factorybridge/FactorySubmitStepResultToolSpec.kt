package io.whozoss.agentos.plugins.factorybridge

import com.fasterxml.jackson.module.kotlin.jacksonObjectMapper
import com.sun.net.httpserver.HttpServer
import io.kotest.core.spec.style.StringSpec
import io.kotest.matchers.shouldBe
import io.whozoss.agentos.plugins.factorybridge.tools.FactorySubmitStepResultTool
import io.whozoss.agentos.sdk.caseEvent.CaseStatusEvent
import io.whozoss.agentos.sdk.caseFlow.CaseStatus
import io.whozoss.agentos.sdk.entity.EntityMetadata
import io.whozoss.agentos.sdk.tool.ToolContext
import okhttp3.OkHttpClient
import java.net.InetSocketAddress
import java.time.Instant
import java.util.UUID

/** Source-only contract scenarios. Execution is deliberately left to the maintainer. */
class FactorySubmitStepResultToolSpec : StringSpec({
    "tool is fail-closed outside a Factory-bound case" {
        val tool =
            FactorySubmitStepResultTool(
                "http://127.0.0.1:8141",
                OkHttpClient(),
                jacksonObjectMapper(),
                FactoryStepResultBindingRegistry(),
            )
        val input = FactorySubmitStepResultTool.Input("PASS", "ok", claims = FactorySubmitStepResultTool.Claims(emptyList()))
        val result = tool.execute(input, ToolContext(UUID.randomUUID(), null, null, emptyList(), "Worker"))
        result.success shouldBe false
        result.errorType shouldBe "FACTORY_WORKER_BINDING_INVALID"
    }

    "wire payload omits null finding location fields" {
        var requestBody: String? = null
        val server = HttpServer.create(InetSocketAddress("127.0.0.1", 0), 0)
        server.createContext("/") { exchange ->
            requestBody = exchange.requestBody.bufferedReader().use { it.readText() }
            val response = """{"data":{"accepted":true}}""".toByteArray()
            exchange.sendResponseHeaders(200, response.size.toLong())
            exchange.responseBody.use { it.write(response) }
        }
        server.start()
        try {
            val namespaceId = UUID.randomUUID()
            val caseId = UUID.randomUUID()
            val bindings = FactoryStepResultBindingRegistry()
            bindings.bind(
                FactoryStepResultBinding(
                    caseId,
                    namespaceId,
                    "Worker",
                    "attempt-1",
                    "runtime-1",
                    "secret-token-value-with-sufficient-length",
                    Instant.now().plusSeconds(60),
                ),
            )
            val context = ToolContext(
                namespaceId,
                UUID.randomUUID(),
                "actor",
                listOf(CaseStatusEvent(metadata = EntityMetadata(), namespaceId = namespaceId, caseId = caseId, status = CaseStatus.RUNNING)),
                "Worker",
            )
            val mapper = jacksonObjectMapper()
            val tool = FactorySubmitStepResultTool(
                "http://127.0.0.1:${server.address.port}",
                OkHttpClient(),
                mapper,
                bindings,
            )

            val result = tool.execute(
                FactorySubmitStepResultTool.Input(
                    status = "PASS",
                    summary = "done",
                    claims = FactorySubmitStepResultTool.Claims(emptyList()),
                    findings = listOf(FactorySubmitStepResultTool.Finding("info", "NOTE", "No location")),
                ),
                context,
            )

            result.success shouldBe true
            val finding = mapper.readTree(requestBody).path("result").path("findings")[0]
            finding.has("file") shouldBe false
            finding.has("line") shouldBe false
        } finally {
            server.stop(0)
        }
    }

    "wire payload preserves present finding location fields" {
        var requestBody: String? = null
        val server = HttpServer.create(InetSocketAddress("127.0.0.1", 0), 0)
        server.createContext("/") { exchange ->
            requestBody = exchange.requestBody.bufferedReader().use { it.readText() }
            val response = """{"data":{"accepted":true}}""".toByteArray()
            exchange.sendResponseHeaders(200, response.size.toLong())
            exchange.responseBody.use { it.write(response) }
        }
        server.start()
        try {
            val namespaceId = UUID.randomUUID()
            val caseId = UUID.randomUUID()
            val bindings = FactoryStepResultBindingRegistry()
            bindings.bind(
                FactoryStepResultBinding(
                    caseId,
                    namespaceId,
                    "Worker",
                    "attempt-1",
                    "runtime-1",
                    "secret-token-value-with-sufficient-length",
                    Instant.now().plusSeconds(60),
                ),
            )
            val context = ToolContext(
                namespaceId,
                UUID.randomUUID(),
                "actor",
                listOf(CaseStatusEvent(metadata = EntityMetadata(), namespaceId = namespaceId, caseId = caseId, status = CaseStatus.RUNNING)),
                "Worker",
            )
            val mapper = jacksonObjectMapper()
            val tool = FactorySubmitStepResultTool(
                "http://127.0.0.1:${server.address.port}",
                OkHttpClient(),
                mapper,
                bindings,
            )

            val result = tool.execute(
                FactorySubmitStepResultTool.Input(
                    status = "FAIL",
                    summary = "review failed",
                    claims = FactorySubmitStepResultTool.Claims(emptyList()),
                    findings = listOf(FactorySubmitStepResultTool.Finding("error", "BUG", "Located", "src/Main.kt", 42)),
                ),
                context,
            )

            result.success shouldBe true
            val finding = mapper.readTree(requestBody).path("result").path("findings")[0]
            finding.path("file").asText() shouldBe "src/Main.kt"
            finding.path("line").asInt() shouldBe 42
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
            FactorySubmitStepResultTool(
                "http://127.0.0.1:8141",
                OkHttpClient(),
                jacksonObjectMapper(),
                FactoryStepResultBindingRegistry(),
            )
        val result = tool.execute(FactorySubmitStepResultTool.Input("PASS", "ok", claims = FactorySubmitStepResultTool.Claims(emptyList())), context)
        result.success shouldBe false
        result.errorType shouldBe "FACTORY_WORKER_BINDING_MISSING"
    }
})
