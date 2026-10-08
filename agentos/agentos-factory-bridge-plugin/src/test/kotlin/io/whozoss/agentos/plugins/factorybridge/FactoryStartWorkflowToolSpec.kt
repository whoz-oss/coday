package io.whozoss.agentos.plugins.factorybridge

import com.fasterxml.jackson.module.kotlin.jacksonObjectMapper
import com.sun.net.httpserver.HttpServer
import io.kotest.core.spec.style.StringSpec
import io.kotest.matchers.shouldBe
import io.whozoss.agentos.plugins.factorybridge.tools.FactoryStartWorkflowTool
import io.whozoss.agentos.sdk.caseEvent.CaseStatusEvent
import io.whozoss.agentos.sdk.caseFlow.CaseStatus
import io.whozoss.agentos.sdk.entity.EntityMetadata
import io.whozoss.agentos.sdk.tool.ToolContext
import okhttp3.OkHttpClient
import java.net.InetSocketAddress
import java.util.UUID

class FactoryStartWorkflowToolSpec : StringSpec({
    val mapper = jacksonObjectMapper()

    "strict schema and trusted HTTP attribution" {
        var path = ""
        var body = ""
        var headers = emptyMap<String, String>()
        val server = HttpServer.create(InetSocketAddress("127.0.0.1", 0), 0)
        server.createContext("/") { ex ->
            if (ex.requestMethod == "POST") {
                path = ex.requestURI.toString()
                body = ex.requestBody.bufferedReader().readText()
                headers = ex.requestHeaders.entries.associate { it.key.lowercase() to it.value.single() }
            } else {
                ex.requestBody.close()
            }
            val bytes =
                """{"data":{"workflowId":"wf-1","title":"Story","revision":1,"created":true,"queued":true,"idempotent":false,"submissionId":"sub-1","submissionStatus":"pending","governanceMode":"governed","definitionVersion":"1.0.0","definitionHash":"hash","projection":{}}}"""
                    .toByteArray()
            ex.sendResponseHeaders(201, bytes.size.toLong())
            ex.responseBody.use { it.write(bytes) }
        }
        server.start()
        try {
            val signer = FactoryTrustedHeaderSigner("test-secret", "agentos-factory-bridge", listOf("workflow:write"), now = { 1234L })
            val tool = FactoryStartWorkflowTool("http://127.0.0.1:${server.address.port}", OkHttpClient(), mapper, signer)
            tool.name shouldBe "FACTORY_WORKSTREAM__start_workflow"
            val schema = mapper.readTree(tool.inputSchema)
            schema.path("additionalProperties").asBoolean() shouldBe false
            schema.path("properties").fieldNames().asSequence().toSet() shouldBe setOf("workflowType", "title", "ticket", "workstream")
            val ns = UUID.randomUUID()
            val case = UUID.randomUUID()
            val context =
                ToolContext(ns, UUID.randomUUID(), "actor-external", listOf(CaseStatusEvent(metadata = EntityMetadata(), namespaceId = ns, caseId = case, status = CaseStatus.PENDING)), "ProductEngineer")
            val trustedContext = context.copy(toolRequestId = "tool-request-1")
            val result = tool.execute(FactoryStartWorkflowTool.Input("bmad-story", "Story", "WZ-35341", "talent-portal"), trustedContext)
            result.success shouldBe true
            val output = mapper.readTree(result.output)
            // Phase 7 standardized command output.
            output.path("status").asText() shouldBe "accepted"
            output.path("revision").asLong() shouldBe 1L
            output.path("allowedActions").isArray shouldBe true
            output.path("created").asBoolean() shouldBe true
            path shouldBe "/api/factory/workflows"
            val sent = mapper.readTree(body)
            sent.fieldNames().asSequence().toSet() shouldBe setOf("workflowType", "title", "parameters")
            sent.path("workflowType").asText() shouldBe "bmad-story"
            sent.path("title").asText() shouldBe "Story"
            sent.path("parameters").path("ticket").asText() shouldBe "WZ-35341"
            sent.path("parameters").path("workstream").asText() shouldBe "talent-portal"
            headers["idempotency-key"] shouldBe "agentos-tool:tool-request-1"
            headers["x-proxy-principal-id"] shouldBe "actor-external"
            headers["x-proxy-principal-type"] shouldBe "service"
            headers["x-proxy-service-identity-id"] shouldBe "agentos-factory-bridge"
            headers["x-proxy-scopes"] shouldBe "workflow:write"
            headers["x-proxy-namespace-id"] shouldBe ns.toString()
            headers["x-proxy-case-id"] shouldBe case.toString()
            headers["x-proxy-timestamp"] shouldBe "1234"
            headers["x-proxy-signature"].isNullOrBlank() shouldBe false
        } finally {
            server.stop(0)
        }
    }

    "maps created idempotent errors and malformed responses" {
        val tool = FactoryStartWorkflowTool(
            "http://localhost",
            OkHttpClient(),
            mapper,
            FactoryTrustedHeaderSigner("test-secret", "agentos-factory-bridge", listOf("workflow:write")),
        )
        tool
            .parseResponse(201, """{"data":{"workflowId":"wf","title":"Run","revision":1,"created":true,"queued":true,"idempotent":false,"submissionId":"sub","submissionStatus":"pending","governanceMode":"governed","definitionVersion":"1.0.0","definitionHash":"h","projection":{}}}""")
            .metadata["created"] shouldBe true
        tool
            .parseResponse(200, """{"data":{"workflowId":"wf","title":"Run","revision":1,"created":false,"queued":false,"idempotent":true,"submissionId":"sub","submissionStatus":"pending","governanceMode":"governed","definitionVersion":"1.0.0","definitionHash":"h","projection":{}}}""")
            .metadata["idempotent"] shouldBe true
        tool.parseResponse(409, """{"error":{"code":"WORKFLOW_REMOVED","message":"removed"}}""").errorType shouldBe "WORKFLOW_REMOVED"
        tool.parseResponse(200, "bad").errorType shouldBe "MALFORMED_FACTORY_RESPONSE"
    }
})
