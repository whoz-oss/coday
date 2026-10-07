package io.whozoss.agentos.plugins.factorybridge

import com.fasterxml.jackson.module.kotlin.jacksonObjectMapper
import com.sun.net.httpserver.HttpServer
import io.kotest.core.spec.style.StringSpec
import io.kotest.matchers.shouldBe
import io.whozoss.agentos.plugins.factorybridge.tools.FactoryGetStepAttemptsTool
import io.whozoss.agentos.sdk.tool.ToolContext
import io.whozoss.agentos.sdk.tool.ToolExecutionResult
import okhttp3.OkHttpClient
import java.net.InetSocketAddress
import java.util.UUID
import java.util.concurrent.TimeUnit

class FactoryGetStepAttemptsToolSpec : StringSpec({
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

    val attemptJson =
        """{
            "attemptId":"attempt-1","stepId":"step-1","attemptNumber":2,"agentName":"builder",
            "status":"failed","caseId":"case-1","failureCode":"ORACLE_FAILED","resultEvidenceId":"ev-1",
            "environmentRef":"env-1","expectedEnvironmentRevision":3,"resumptionContext":"internal",
            "revision":5,"createdAt":"2026-01-01T00:00:00Z","startedAt":"2026-01-01T00:01:00Z","completedAt":"2026-01-01T00:02:00Z"
        }"""

    "schema exposes workflowId and stepId only, both required" {
        val tool = FactoryGetStepAttemptsTool("http://localhost", OkHttpClient(), mapper)
        val schema = mapper.readTree(tool.inputSchema)
        schema.path("properties").fieldNames().asSequence().toSet() shouldBe setOf("workflowId", "stepId")
        schema.path("required").map { it.asText() }.toSet() shouldBe setOf("workflowId", "stepId")
        schema.path("additionalProperties").asBoolean() shouldBe false
        schema.path("properties").path("stepId").path("maxLength").asInt() shouldBe 128
    }

    "request injects namespace and step filter and returns the bounded secret-free attempts" {
        var requestedPath: String? = null
        val (server, baseUrl) = serve(200, """{"data":[$attemptJson]}""") { requestedPath = it }
        try {
            val namespace = UUID.randomUUID()
            val tool = FactoryGetStepAttemptsTool(baseUrl, OkHttpClient(), mapper)
            val result =
                tool.execute(FactoryGetStepAttemptsTool.Input("wf-1", "step-1"), ToolContext(namespace, null, null, emptyList(), "agent"))
            result.success shouldBe true
            requestedPath shouldBe "/api/factory/workflows/wf-1/attempts?namespaceId=$namespace&stepId=step-1"
            val attempts = mapper.readTree(result.output)
            attempts.isArray shouldBe true
            attempts.size() shouldBe 1
            val first = attempts[0]
            first.fieldNames().asSequence().toSet() shouldBe setOf(
                "attemptId", "stepId", "attemptNumber", "agentName", "status", "caseId",
                "failureCode", "resultEvidenceId", "revision", "createdAt", "startedAt", "completedAt",
            )
            first.path("attemptNumber").asInt() shouldBe 2
            first.path("failureCode").asText() shouldBe "ORACLE_FAILED"
            first.path("resultEvidenceId").asText() shouldBe "ev-1"
            // No secret, no internal recovery field, no raw prose ever crosses.
            val serialized = result.output
            listOf(
                "ownerToken", "capabilityToken", "commandId", "brief", "leaseExpiresAt",
                "lastObservedEventId", "turnCorrelation", "resumptionContext", "environmentRef",
            ).forEach { (serialized.contains(it)) shouldBe false }
        } finally {
            server.stop(0)
        }
    }

    "unknown workflow or step degrades to an empty array" {
        val (server, baseUrl) = serve(200, """{"data":[]}""")
        try {
            val tool = FactoryGetStepAttemptsTool(baseUrl, OkHttpClient(), mapper)
            val result =
                tool.execute(
                    FactoryGetStepAttemptsTool.Input("wf-unknown", "step-unknown"),
                    ToolContext(UUID.randomUUID(), null, null, emptyList(), "agent"),
                )
            result.success shouldBe true
            mapper.readTree(result.output).size() shouldBe 0
        } finally {
            server.stop(0)
        }
    }

    "rejects invalid identifiers before any network call" {
        val tool = FactoryGetStepAttemptsTool("http://localhost", OkHttpClient(), mapper)
        val context = ToolContext(UUID.randomUUID(), null, null, emptyList(), "agent")
        tool.execute(FactoryGetStepAttemptsTool.Input("bad id", "step-1"), context).errorType shouldBe "INVALID_WORKFLOW_ID"
        tool.execute(FactoryGetStepAttemptsTool.Input("", "step-1"), context).errorType shouldBe "INVALID_WORKFLOW_ID"
        tool.execute(FactoryGetStepAttemptsTool.Input("wf-1", ""), context).errorType shouldBe "INVALID_REQUEST"
        tool.execute(FactoryGetStepAttemptsTool.Input("wf-1", "step/1"), context).errorType shouldBe "INVALID_REQUEST"
        tool.execute(FactoryGetStepAttemptsTool.Input("wf-1", "x".repeat(129)), context).errorType shouldBe "INVALID_REQUEST"
        tool.execute(null, context).errorType shouldBe "INVALID_WORKFLOW_ID"
    }

    "Factory errors and malformed bodies map to the stable codes" {
        suspend fun call(status: Int, body: String): ToolExecutionResult {
            val (server, baseUrl) = serve(status, body)
            return try {
                FactoryGetStepAttemptsTool(baseUrl, OkHttpClient(), mapper)
                    .execute(
                        FactoryGetStepAttemptsTool.Input("wf-1", "step-1"),
                        ToolContext(UUID.randomUUID(), null, null, emptyList(), "agent"),
                    )
            } finally {
                server.stop(0)
            }
        }
        call(404, """{"error":{"code":"WORKFLOW_NOT_FOUND","message":"missing"}}""").errorType shouldBe "WORKFLOW_NOT_FOUND"
        call(403, """{"error":{"code":"WORKSTREAM_BOUNDARY_VIOLATION","message":"outside"}}""")
            .errorType shouldBe "WORKSTREAM_BOUNDARY_VIOLATION"
        call(500, """{"unexpected":true}""").errorType shouldBe "FACTORY_REQUEST_FAILED"
        call(200, "not-json").errorType shouldBe "MALFORMED_FACTORY_RESPONSE"
        call(200, """{"data":{}}""").errorType shouldBe "MALFORMED_FACTORY_RESPONSE"
    }

    "timeout and connection failure map to the transport codes" {
        val server = HttpServer.create(InetSocketAddress("127.0.0.1", 0), 0)
        server.createContext("/") { exchange ->
            Thread.sleep(2_000)
            val bytes = """{"data":[]}""".toByteArray()
            exchange.sendResponseHeaders(200, bytes.size.toLong())
            exchange.responseBody.use { it.write(bytes) }
        }
        server.start()
        try {
            val impatient = OkHttpClient.Builder().readTimeout(100, TimeUnit.MILLISECONDS).build()
            val tool = FactoryGetStepAttemptsTool("http://127.0.0.1:${server.address.port}", impatient, mapper)
            tool.execute(
                FactoryGetStepAttemptsTool.Input("wf-1", "step-1"),
                ToolContext(UUID.randomUUID(), null, null, emptyList(), "agent"),
            ).errorType shouldBe "FACTORY_TIMEOUT"
        } finally {
            server.stop(0)
        }

        val closed = HttpServer.create(InetSocketAddress("127.0.0.1", 0), 0)
        closed.start()
        val port = closed.address.port
        closed.stop(0)
        FactoryGetStepAttemptsTool("http://127.0.0.1:$port", OkHttpClient(), mapper)
            .execute(
                FactoryGetStepAttemptsTool.Input("wf-1", "step-1"),
                ToolContext(UUID.randomUUID(), null, null, emptyList(), "agent"),
            ).errorType shouldBe "FACTORY_UNAVAILABLE"
    }
})
