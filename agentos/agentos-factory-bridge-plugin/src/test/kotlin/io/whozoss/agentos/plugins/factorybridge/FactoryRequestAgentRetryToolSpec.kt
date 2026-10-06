package io.whozoss.agentos.plugins.factorybridge

import com.fasterxml.jackson.module.kotlin.jacksonObjectMapper
import com.sun.net.httpserver.HttpServer
import io.kotest.core.spec.style.StringSpec
import io.kotest.matchers.shouldBe
import io.whozoss.agentos.plugins.factorybridge.tools.FactoryRequestAgentRetryTool
import io.whozoss.agentos.sdk.caseEvent.CaseStatusEvent
import io.whozoss.agentos.sdk.caseFlow.CaseStatus
import io.whozoss.agentos.sdk.entity.EntityMetadata
import io.whozoss.agentos.sdk.tool.ToolContext
import okhttp3.OkHttpClient
import java.net.InetSocketAddress
import java.util.UUID

class FactoryRequestAgentRetryToolSpec : StringSpec({
    "retry uses shared signed trust headers and keeps trusted identity out of the body" {
        val mapper = jacksonObjectMapper()
        var body = ""
        var headers = emptyMap<String, String>()
        val server = HttpServer.create(InetSocketAddress("127.0.0.1", 0), 0)
        server.createContext("/") { ex ->
            val bytes =
                if (ex.requestMethod == "POST") {
                    body = ex.requestBody.bufferedReader().readText()
                    headers = ex.requestHeaders.entries.associate { it.key.lowercase() to it.value.single() }
                    """{"data":{"workflowId":"wf-1","interaction":{"interactionId":"i-1","revision":2,"stepId":"step-1"}}}""".toByteArray()
                } else {
                    ex.requestBody.close()
                    """{"data":{"allowedActions":[]}}""".toByteArray()
                }
            ex.sendResponseHeaders(200, bytes.size.toLong())
            ex.responseBody.use { it.write(bytes) }
        }
        server.start()
        try {
            val signer = FactoryTrustedHeaderSigner("test-secret", "agentos-factory-bridge", listOf("workflow:write"), now = { 1234L })
            val tool = FactoryRequestAgentRetryTool("http://127.0.0.1:${server.address.port}", OkHttpClient(), mapper, signer)
            val namespaceId = UUID.randomUUID()
            val caseId = UUID.randomUUID()
            val context = ToolContext(
                namespaceId,
                UUID.randomUUID(),
                "actor-external",
                listOf(CaseStatusEvent(metadata = EntityMetadata(), namespaceId = namespaceId, caseId = caseId, status = CaseStatus.PENDING)),
                "ProductEngineer",
            )
            val result = tool.execute(FactoryRequestAgentRetryTool.Input("wf-1", "step-1", 1, "blocked"), context)
            result.success shouldBe true
            mapper.readTree(body).fieldNames().asSequence().toSet() shouldBe setOf("stepId", "expectedRevision", "reasonCode")
            headers["x-proxy-principal-id"] shouldBe "actor-external"
            headers["x-proxy-namespace-id"] shouldBe namespaceId.toString()
            headers["x-proxy-case-id"] shouldBe caseId.toString()
            headers["x-proxy-signature"].isNullOrBlank() shouldBe false
            headers["x-factory-namespace-id"] shouldBe null
            headers["x-factory-case-id"] shouldBe null
        } finally {
            server.stop(0)
        }
    }
})
