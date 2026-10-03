package io.whozoss.agentos.plugins.factorybridge

import com.fasterxml.jackson.module.kotlin.jacksonObjectMapper
import com.sun.net.httpserver.HttpServer
import io.kotest.core.spec.style.StringSpec
import io.kotest.matchers.shouldBe
import io.whozoss.agentos.plugins.factorybridge.tools.FactoryRecordAgentResultTool
import io.whozoss.agentos.plugins.factorybridge.tools.FactoryRecordArtifactTool
import io.whozoss.agentos.sdk.caseEvent.CaseStatusEvent
import io.whozoss.agentos.sdk.caseFlow.CaseStatus
import io.whozoss.agentos.sdk.entity.EntityMetadata
import io.whozoss.agentos.sdk.tool.ToolContext
import io.whozoss.agentos.sdk.tool.ToolExecutionResult
import okhttp3.OkHttpClient
import java.net.InetSocketAddress
import java.util.UUID

class FactoryRecordEvidenceToolSpec : StringSpec({
    val mapper = jacksonObjectMapper()

    "strict business-only schemas and trusted source for both evidence kinds" {
        val bodies = mutableListOf<String>()
        val paths = mutableListOf<String>()
        val server = HttpServer.create(InetSocketAddress("127.0.0.1", 0), 0)
        server.createContext("/") { ex ->
            paths += ex.requestURI.toString()
            bodies += ex.requestBody.bufferedReader().readText()
            val bytes = """{"data":{"created":true,"idempotent":false,"evidence":{"evidenceId":"factory-id"}}}""".toByteArray()
            ex.sendResponseHeaders(201, bytes.size.toLong())
            ex.responseBody.use { it.write(bytes) }
        }
        server.start()
        try {
            val ns = UUID.randomUUID()
            val case = UUID.randomUUID()
            val context =
                ToolContext(ns, UUID.randomUUID(), "actor-external", listOf(CaseStatusEvent(metadata = EntityMetadata(), namespaceId = ns, caseId = case, status = CaseStatus.PENDING)), "ProductEngineer")
            val agent = FactoryRecordAgentResultTool("http://127.0.0.1:${server.address.port}", OkHttpClient(), mapper, "runtime-configured")
            val artifact = FactoryRecordArtifactTool("http://127.0.0.1:${server.address.port}", OkHttpClient(), mapper, "runtime-configured")
            mapper.readTree(agent.inputSchema).path("properties").fieldNames().asSequence().toSet() shouldBe
                setOf("workflowId", "stepId", "outcome", "facts", "idempotencyKey")
            mapper.readTree(artifact.inputSchema).path("properties").fieldNames().asSequence().toSet() shouldBe
                setOf("workflowId", "stepId", "artifactRef", "artifactHash", "idempotencyKey")
            mapper.readTree(agent.inputSchema).path("additionalProperties").asBoolean() shouldBe false
            agent.execute(FactoryRecordAgentResultTool.Input("wf-1", "implement", FactoryRecordAgentResultTool.Facts(resultCode = "DONE", attempt = 1), "pass", "turn-1"), context).success shouldBe true
            artifact.execute(FactoryRecordArtifactTool.Input("wf-1", "implement", "opaque://artifact", "sha256:${"a".repeat(64)}", "artifact-1"), context).success shouldBe true
            paths shouldBe listOf("/api/factory/workflows/wf-1/evidence", "/api/factory/workflows/wf-1/evidence")
            val sent = mapper.readTree(bodies.first())
            sent.path("evidence").fieldNames().asSequence().toSet() shouldBe setOf("workflowId", "stepId", "facts", "outcome", "idempotencyKey", "kind")
            sent.path("execution").path("namespaceId").asText() shouldBe ns.toString()
            sent.path("execution").path("runtimeId").asText() shouldBe "runtime-configured"
            sent.path("execution").path("caseId").asText() shouldBe case.toString()
            sent.path("execution").path("agentId").asText() shouldBe "ProductEngineer"
            sent.path("execution").path("actorId").asText() shouldBe "actor-external"
        } finally {
            server.stop(0)
        }
    }

    "maps idempotent success Factory errors and malformed responses" {
        suspend fun execute(
            status: Int,
            response: String,
        ): ToolExecutionResult {
            val server = HttpServer.create(InetSocketAddress("127.0.0.1", 0), 0)
            server.createContext("/") { exchange ->
                exchange.requestBody.close()
                val bytes = response.toByteArray()
                exchange.sendResponseHeaders(status, bytes.size.toLong())
                exchange.responseBody.use { it.write(bytes) }
            }
            server.start()
            return try {
                val namespaceId = UUID.randomUUID()
                val caseId = UUID.randomUUID()
                val context =
                    ToolContext(
                        namespaceId,
                        UUID.randomUUID(),
                        "actor",
                        listOf(CaseStatusEvent(metadata = EntityMetadata(), namespaceId = namespaceId, caseId = caseId, status = CaseStatus.PENDING)),
                        "agent",
                    )
                FactoryRecordArtifactTool("http://127.0.0.1:${server.address.port}", OkHttpClient(), mapper, "runtime").execute(
                    FactoryRecordArtifactTool.Input("wf", "step", "ref", "sha256:${"a".repeat(64)}"),
                    context,
                )
            } finally {
                server.stop(0)
            }
        }

        val idempotent = execute(200, """{"data":{"created":false,"idempotent":true,"evidence":{}}}""")
        idempotent.success shouldBe true
        val collision = execute(409, """{"error":{"code":"IDEMPOTENCY_KEY_COLLISION","message":"collision"}}""")
        collision.errorType shouldBe "IDEMPOTENCY_KEY_COLLISION"
        val malformed = execute(200, "bad")
        malformed.errorType shouldBe "MALFORMED_FACTORY_RESPONSE"
    }

    "grants are exact independent and near capabilities are refused" {
        val grant = FactoryTestFixtures.grantService()
        val context = ToolContext(UUID.randomUUID(), null, null, emptyList())
        grant.grantTools(context, mapOf("FACTORY" to listOf("record_agent_result"))).map { it.name } shouldBe listOf("FACTORY__record_agent_result")
        grant.grantTools(context, mapOf("FACTORY" to listOf("record_artifact"))).map { it.name } shouldBe listOf("FACTORY__record_artifact")
        grant.grantTools(context, mapOf("FACTORY" to listOf("record_artifacts"))).isEmpty() shouldBe true
        grant.grantTools(context, null).isEmpty() shouldBe true
    }
})
