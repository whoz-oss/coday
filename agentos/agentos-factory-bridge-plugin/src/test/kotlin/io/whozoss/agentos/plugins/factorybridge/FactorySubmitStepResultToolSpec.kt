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
    val mapper = jacksonObjectMapper()

    "schema advertises all statuses and strict optional delivery analysis" {
        val tool = FactorySubmitStepResultTool("http://localhost", OkHttpClient(), mapper, FactoryStepResultBindingRegistry())
        val schema = mapper.readTree(tool.inputSchema)
        schema.path("additionalProperties").asBoolean() shouldBe false
        schema.path("properties").path("status").path("enum").map { it.asText() } shouldBe
            listOf("PASS", "FAIL", "NEEDS_RESEARCH", "CHANGES_REQUESTED")
        val claims = schema.path("properties").path("claims")
        claims.path("additionalProperties").asBoolean() shouldBe false
        claims.path("required").map { it.asText() } shouldBe listOf("modifiedFiles")
        val analysis = claims.path("properties").path("deliveryAnalysis")
        analysis.path("additionalProperties").asBoolean() shouldBe false
        analysis.path("required").map { it.asText() } shouldBe listOf("changeType", "ticket", "title")
        analysis.path("properties").path("changeType").path("enum").map { it.asText() } shouldBe listOf("feature", "bugfix")
        analysis.path("properties").path("ticket").path("pattern").asText() shouldBe "^WZ-[0-9]+$"
        analysis.path("properties").path("title").path("minLength").asInt() shouldBe 1
        analysis.path("properties").path("title").path("maxLength").asInt() shouldBe 200
        analysis.path("properties").path("baseBranch").path("maxLength").asInt() shouldBe 255
    }

    "typed input deserializes optional delivery analysis" {
        val input = mapper.readValue(
            """{"status":"NEEDS_RESEARCH","summary":"analyze","claims":{"modifiedFiles":[],"deliveryAnalysis":{"changeType":"feature","ticket":"WZ-35278","title":"Factory bridge","baseBranch":"master"}}}""",
            FactorySubmitStepResultTool.Input::class.java,
        )
        input.claims.deliveryAnalysis shouldBe FactorySubmitStepResultTool.DeliveryAnalysis(
            changeType = "feature",
            ticket = "WZ-35278",
            title = "Factory bridge",
            baseBranch = "master",
        )
    }

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

    "wire payload transmits analysis and omits absent optional values for legacy and research results" {
        val bodies = mutableListOf<String>()
        val server = HttpServer.create(InetSocketAddress("127.0.0.1", 0), 0)
        server.createContext("/") { exchange ->
            bodies += exchange.requestBody.bufferedReader().use { it.readText() }
            val response = """{"data":{"accepted":true}}""".toByteArray()
            exchange.sendResponseHeaders(200, response.size.toLong())
            exchange.responseBody.use { it.write(response) }
        }
        server.start()
        try {
            suspend fun submit(input: FactorySubmitStepResultTool.Input) {
                val namespaceId = UUID.randomUUID()
                val caseId = UUID.randomUUID()
                val bindings = FactoryStepResultBindingRegistry()
                bindings.bind(
                    FactoryStepResultBinding(
                        caseId,
                        namespaceId,
                        "Worker",
                        "attempt-${bodies.size}",
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
                FactorySubmitStepResultTool(
                    "http://127.0.0.1:${server.address.port}",
                    OkHttpClient(),
                    mapper,
                    bindings,
                ).execute(input, context).success shouldBe true
            }

            submit(
                FactorySubmitStepResultTool.Input(
                    status = "PASS",
                    summary = "legacy",
                    claims = FactorySubmitStepResultTool.Claims(emptyList()),
                ),
            )
            submit(
                FactorySubmitStepResultTool.Input(
                    status = "NEEDS_RESEARCH",
                    summary = "analysis needed",
                    claims = FactorySubmitStepResultTool.Claims(
                        emptyList(),
                        FactorySubmitStepResultTool.DeliveryAnalysis("bugfix", "WZ-35278", "Bridge result contract"),
                    ),
                ),
            )
            submit(
                FactorySubmitStepResultTool.Input(
                    status = "CHANGES_REQUESTED",
                    summary = "review changes",
                    claims = FactorySubmitStepResultTool.Claims(
                        listOf("src/Main.kt"),
                        FactorySubmitStepResultTool.DeliveryAnalysis("feature", "WZ-35278", "Bridge result contract", "master"),
                    ),
                    findings = listOf(FactorySubmitStepResultTool.Finding("blocking", "CONTRACT", "Update the contract")),
                ),
            )

            val legacyClaims = mapper.readTree(bodies[0]).path("result").path("claims")
            legacyClaims.fieldNames().asSequence().toSet() shouldBe setOf("modifiedFiles")
            val researchAnalysis = mapper.readTree(bodies[1]).path("result").path("claims").path("deliveryAnalysis")
            researchAnalysis.fieldNames().asSequence().toSet() shouldBe setOf("changeType", "ticket", "title")
            researchAnalysis.has("baseBranch") shouldBe false
            val changed = mapper.readTree(bodies[2]).path("result")
            changed.path("status").asText() shouldBe "CHANGES_REQUESTED"
            changed.path("claims").path("deliveryAnalysis").path("baseBranch").asText() shouldBe "master"
            changed.path("findings")[0].path("severity").asText() shouldBe "blocking"
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
