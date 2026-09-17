package io.whozoss.agentos.factory

import com.fasterxml.jackson.module.kotlin.jacksonObjectMapper
import io.kotest.core.spec.style.StringSpec
import io.kotest.matchers.shouldBe
import io.whozoss.agentos.sdk.tool.ToolContext
import com.sun.net.httpserver.HttpServer
import java.net.InetSocketAddress
import java.util.UUID

class FactoryGetWorkflowToolSpec : StringSpec({
    val mapper = jacksonObjectMapper()

    "schema exposes workflowId only and request injects namespace from ToolContext" {
        var requestedPath: String? = null
        val server = HttpServer.create(InetSocketAddress("127.0.0.1", 0), 0)
        server.createContext("/") { exchange ->
            requestedPath = exchange.requestURI.toString()
            val body = """{"data":{"namespaceId":"ignored","workflowId":"wf-1","state":"absent"}}""".toByteArray()
            exchange.sendResponseHeaders(200, body.size.toLong())
            exchange.responseBody.use { it.write(body) }
        }
        server.start()
        try {
            val tool = FactoryGetWorkflowTool("http://127.0.0.1:${server.address.port}", okhttp3.OkHttpClient(), mapper)
            mapper.readTree(tool.inputSchema).path("properties").fieldNames().asSequence().toList() shouldBe listOf("workflowId")
            val namespace = UUID.randomUUID()
            tool.execute(FactoryGetWorkflowTool.Input("wf-1"), ToolContext(namespace, null, null, emptyList(), "agent")).success shouldBe true
            requestedPath shouldBe "/api/factory/workflows/wf-1?namespaceId=$namespace"
        } finally { server.stop(0) }
    }

    "maps absent existing removed and purged states while preserving v1 and v2 snapshots" {
        val tool = FactoryGetWorkflowTool("http://localhost", okhttp3.OkHttpClient(), mapper)
        for (state in listOf("absent", "removed", "purged")) {
            val result = tool.parseResponse("wf-1", 200, """{"data":{"workflowId":"wf-1","state":"$state"}}""")
            result.success shouldBe true
            mapper.readTree(result.output).path("state").asText() shouldBe state
        }
        for (version in listOf("1", "2")) {
            val steps = if (version == "1") "[]" else "[{\"id\":\"s\",\"name\":\"S\",\"status\":\"ready\",\"dependsOn\":[],\"responsibility\":{\"kind\":\"agent\"}}]"
            val body = """{"data":{"workflowId":"wf-1","state":"existing","revision":7,"projection":{"schemaVersion":"$version","workflowId":"wf-1","workflowType":"delivery","title":"W","status":"ready","steps":$steps}}}"""
            val result = tool.parseResponse("wf-1", 200, body)
            result.success shouldBe true
            val output = mapper.readTree(result.output)
            output.path("revision").asLong() shouldBe 7L
            output.path("projection").path("schemaVersion").asText() shouldBe version
        }
    }

    "rejects malformed state or mismatched workflow identity" {
        val tool = FactoryGetWorkflowTool("http://localhost", okhttp3.OkHttpClient(), mapper)
        tool.parseResponse("wf-1", 200, """{"data":{"workflowId":"other","state":"absent"}}""").errorType shouldBe "MALFORMED_FACTORY_RESPONSE"
        tool.parseResponse("wf-1", 200, """{"data":{"workflowId":"wf-1","state":"unknown"}}""").errorType shouldBe "MALFORMED_FACTORY_RESPONSE"
    }

    "grant filtering exposes only declared Factory capabilities" {
        val plugin = FactoryToolPlugin(mapper, "http://localhost:3141", "agentos-test")
        val grant = FactoryToolGrantService(plugin)
        val context = ToolContext(UUID.randomUUID(), null, null, emptyList(), "agent")
        grant.grantTools(context, mapOf("FACTORY" to listOf("get_workflow"))).map { it.name } shouldBe listOf("FACTORY__get_workflow")
        grant.grantTools(context, mapOf("FACTORY" to listOf("publish_projection"))).map { it.name } shouldBe listOf("FACTORY__publish_projection")
        grant.grantTools(context, mapOf("FACTORY" to listOf("get_workflow", "publish_projection"))).map { it.name }.toSet() shouldBe setOf("FACTORY__get_workflow", "FACTORY__publish_projection")
        grant.grantTools(context, mapOf("FACTORY" to emptyList())).isEmpty() shouldBe true
    }
})
