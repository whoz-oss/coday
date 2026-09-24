package io.whozoss.agentos.plugins.factorybridge

import com.fasterxml.jackson.module.kotlin.jacksonObjectMapper
import com.sun.net.httpserver.HttpServer
import io.kotest.core.spec.style.StringSpec
import io.kotest.matchers.shouldBe
import io.kotest.matchers.collections.shouldContainExactly
import io.whozoss.agentos.plugins.factorybridge.tools.FactoryGetWorkflowTool
import io.whozoss.agentos.sdk.tool.ToolContext
import okhttp3.OkHttpClient
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
            val tool = FactoryGetWorkflowTool("http://127.0.0.1:${server.address.port}", OkHttpClient(), mapper)
            mapper.readTree(tool.inputSchema).path("properties").fieldNames().asSequence().toList() shouldBe listOf("workflowId")
            val namespace = UUID.randomUUID()
            tool.execute(FactoryGetWorkflowTool.Input("wf-1"), ToolContext(namespace, null, null, emptyList(), "agent")).success shouldBe true
            requestedPath shouldBe "/api/factory/workflows/wf-1?namespaceId=$namespace"
        } finally {
            server.stop(0)
        }
    }

    "maps absent existing removed and purged states while preserving v1 and v2 snapshots" {
        val tool = FactoryGetWorkflowTool("http://localhost", OkHttpClient(), mapper)
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
        val tool = FactoryGetWorkflowTool("http://localhost", OkHttpClient(), mapper)
        tool.parseResponse("wf-1", 200, """{"data":{"workflowId":"other","state":"absent"}}""").errorType shouldBe "MALFORMED_FACTORY_RESPONSE"
        tool.parseResponse("wf-1", 200, """{"data":{"workflowId":"wf-1","state":"unknown"}}""").errorType shouldBe "MALFORMED_FACTORY_RESPONSE"
    }

    "grant filtering exposes only declared Factory capabilities" {
        val grant = FactoryTestFixtures.grantService()
        val context = ToolContext(UUID.randomUUID(), null, null, emptyList(), "agent")
        grant.grantTools(context, mapOf("FACTORY" to listOf("get_workflow"))).map { it.name } shouldBe listOf("FACTORY__get_workflow")
        grant.grantTools(context, mapOf("FACTORY" to listOf("publish_projection"))).map { it.name } shouldBe listOf("FACTORY__publish_projection")
        grant.grantTools(context, mapOf("FACTORY" to listOf("get_workflow", "publish_projection"))).map { it.name }.toSet() shouldBe
            setOf("FACTORY__get_workflow", "FACTORY__publish_projection")
        grant.grantTools(context, mapOf("FACTORY" to emptyList())).isEmpty() shouldBe true
    }

    "tool plugin exposes the full FACTORY capability set and is config-less" {
        val plugin = FactoryToolPlugin { FactoryTestFixtures.services() }
        plugin.integrationType shouldBe "FACTORY"
        plugin.configSchema shouldBe null
        plugin
            .provideTools(null, null, ToolContext(UUID.randomUUID(), null, null, emptyList()))
            .map { it.name }
            .shouldContainExactly(
                "FACTORY__get_workflow",
                "FACTORY__provision_environment",
                "FACTORY__start_workflow",
                "FACTORY__record_agent_result",
                "FACTORY__record_artifact",
                "FACTORY__submit_step_result",
                "FACTORY__request_human_decision",
                "FACTORY__request_transition",
                "FACTORY__transition_workflow",
                "FACTORY__publish_projection",
            )
    }
})
