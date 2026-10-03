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

    "existing read merges Factory-calculated allowed actions blockers attempts and summarized evidence" {
        val requestedPaths = mutableListOf<String>()
        val server = HttpServer.create(InetSocketAddress("127.0.0.1", 0), 0)
        server.createContext("/") { exchange ->
            val path = exchange.requestURI.toString()
            requestedPaths += path
            val body =
                when {
                    path.startsWith("/api/factory/workflows/wf-1/actions") ->
                        """{"data":{"allowedActions":[{"type":"retry","stepId":"s","expectedRevision":7}],"blockers":[{"code":"STEP_BLOCKED","stepId":"s","message":"blocked"}]}}"""
                    path.startsWith("/api/factory/workflows/wf-1/attempts") ->
                        """{"data":[{"attemptId":"a-1","stepId":"s","attemptNumber":1,"agentName":"builder","status":"failed","caseId":"c-1","failureCode":"ORACLE_FAILED","resultEvidenceId":"ev-1","environmentRef":"env-1","resumptionContext":"internal","revision":3,"createdAt":"2026-01-01T00:00:00Z","startedAt":null,"completedAt":"2026-01-01T00:01:00Z"}]}"""
                    path.startsWith("/api/factory/workflows/wf-1/evidence") ->
                        """{"data":{"namespaceId":"ns","workflowId":"wf-1","items":[{"evidenceId":"ev-1","stepId":"s","kind":"oracle","outcome":"failed","facts":{"body":"raw prose that must not cross"},"createdAt":"2026-01-01T00:01:00Z"}]}}"""
                    else ->
                        """{"data":{"workflowId":"wf-1","state":"existing","revision":7,"projection":{"schemaVersion":"2","workflowId":"wf-1","workflowType":"delivery","title":"W","status":"ready","steps":[{"id":"s","name":"S","status":"ready","dependsOn":[],"responsibility":{"kind":"agent"}}]}}}"""
                }
            val bytes = body.toByteArray()
            exchange.sendResponseHeaders(200, bytes.size.toLong())
            exchange.responseBody.use { it.write(bytes) }
        }
        server.start()
        try {
            val namespace = UUID.randomUUID()
            val tool = FactoryGetWorkflowTool("http://127.0.0.1:${server.address.port}", OkHttpClient(), mapper)
            val result = tool.execute(FactoryGetWorkflowTool.Input("wf-1"), ToolContext(namespace, null, null, emptyList(), "agent"))
            result.success shouldBe true
            // The three sub-reads are issued against the same trusted namespace.
            requestedPaths.toSet() shouldBe setOf(
                "/api/factory/workflows/wf-1?namespaceId=$namespace",
                "/api/factory/workflows/wf-1/actions?namespaceId=$namespace",
                "/api/factory/workflows/wf-1/attempts?namespaceId=$namespace",
                "/api/factory/workflows/wf-1/evidence?namespaceId=$namespace",
            )
            val output = mapper.readTree(result.output)
            output.path("state").asText() shouldBe "existing"
            output.path("revision").asLong() shouldBe 7L
            // allowedActions is calculated by the Factory and returned verbatim.
            output.path("allowedActions").size() shouldBe 1
            output.path("allowedActions")[0].path("type").asText() shouldBe "retry"
            output.path("allowedActions")[0].path("expectedRevision").asInt() shouldBe 7
            output.path("blockers").size() shouldBe 1
            output.path("blockers")[0].path("code").asText() shouldBe "STEP_BLOCKED"
            // Attempts are bounded to the secret-free DTO fields.
            val attempt = output.path("attempts")[0]
            attempt.fieldNames().asSequence().toSet() shouldBe setOf(
                "attemptId", "stepId", "attemptNumber", "agentName", "status", "caseId",
                "failureCode", "resultEvidenceId", "revision", "createdAt", "startedAt", "completedAt",
            )
            // Evidence is summarized to refs/metadata only — no free-text body.
            val evidence = output.path("evidence")[0]
            evidence.fieldNames().asSequence().toSet() shouldBe setOf("evidenceId", "stepId", "kind", "createdAt")
            evidence.path("kind").asText() shouldBe "oracle"
            val serialized = result.output
            listOf(
                "ownerToken", "capabilityToken", "commandId", "brief", "leaseExpiresAt",
                "lastObservedEventId", "turnCorrelation", "resumptionContext", "environmentRef",
                "facts", "raw prose",
            ).forEach { serialized.contains(it) shouldBe false }
        } finally {
            server.stop(0)
        }
    }

    "sub-read failures degrade to empty sections while the projection stays authoritative" {
        val server = HttpServer.create(InetSocketAddress("127.0.0.1", 0), 0)
        server.createContext("/") { exchange ->
            val path = exchange.requestURI.toString()
            when {
                path.startsWith("/api/factory/workflows/wf-1/actions") -> {
                    val bytes = "boom".toByteArray()
                    exchange.sendResponseHeaders(500, bytes.size.toLong())
                    exchange.responseBody.use { it.write(bytes) }
                }
                path.startsWith("/api/factory/workflows/wf-1/attempts") -> {
                    val bytes = "not-json".toByteArray()
                    exchange.sendResponseHeaders(200, bytes.size.toLong())
                    exchange.responseBody.use { it.write(bytes) }
                }
                path.startsWith("/api/factory/workflows/wf-1/evidence") -> {
                    val bytes = """{"error":{"code":"WORKFLOW_NOT_FOUND","message":"missing"}}""".toByteArray()
                    exchange.sendResponseHeaders(404, bytes.size.toLong())
                    exchange.responseBody.use { it.write(bytes) }
                }
                else -> {
                    val body =
                        """{"data":{"workflowId":"wf-1","state":"existing","revision":7,"projection":{"schemaVersion":"1","workflowId":"wf-1","workflowType":"delivery","title":"W","status":"ready","steps":[]}}}"""
                    val bytes = body.toByteArray()
                    exchange.sendResponseHeaders(200, bytes.size.toLong())
                    exchange.responseBody.use { it.write(bytes) }
                }
            }
        }
        server.start()
        try {
            val tool = FactoryGetWorkflowTool("http://127.0.0.1:${server.address.port}", OkHttpClient(), mapper)
            val result =
                tool.execute(FactoryGetWorkflowTool.Input("wf-1"), ToolContext(UUID.randomUUID(), null, null, emptyList(), "agent"))
            result.success shouldBe true
            val output = mapper.readTree(result.output)
            output.path("revision").asLong() shouldBe 7L
            output.path("projection").path("workflowType").asText() shouldBe "delivery"
            output.path("allowedActions").size() shouldBe 0
            output.path("blockers").size() shouldBe 0
            output.path("attempts").size() shouldBe 0
            output.path("evidence").size() shouldBe 0
        } finally {
            server.stop(0)
        }
    }

    "read-only grant set exposes exactly the six Workstream Agent reads and stays Neutral in the grant policy" {
        val grant = FactoryTestFixtures.grantService()
        val context = ToolContext(UUID.randomUUID(), null, null, emptyList(), "agent")
        val readSuffixes =
            listOf("get_workstream", "list_workflows", "get_workflow", "get_step_attempts", "get_blockers", "get_required_human_actions")
        grant.grantTools(context, mapOf("FACTORY" to readSuffixes)).map { it.name }.toSet() shouldBe
            setOf(
                "FACTORY__get_workstream",
                "FACTORY__list_workflows",
                "FACTORY__get_workflow",
                "FACTORY__get_step_attempts",
                "FACTORY__get_blockers",
                "FACTORY__get_required_human_actions",
            )
        // A worker suffix never appears alongside the read set unless explicitly listed.
        grant.grantTools(context, mapOf("FACTORY" to readSuffixes)).map { it.name }
            .none { it in setOf("FACTORY__submit_step_result", "FACTORY__ask_step_question", "FACTORY__record_agent_result", "FACTORY__record_artifact", "FACTORY__request_transition", "FACTORY__transition_workflow", "FACTORY__start_workflow", "FACTORY__provision_environment", "FACTORY__request_human_decision", "FACTORY__publish_projection") } shouldBe true
        grant.grantTools(context, mapOf("FACTORY" to emptyList())).isEmpty() shouldBe true
        // The read tools are intentionally not capability-gated: the policy stays Neutral.
        val policy = FactoryToolGrantPolicy { FactoryTestFixtures.services() }
        readSuffixes.forEach { suffix ->
            policy.evaluateToolGrant("agent", "FACTORY__$suffix", context) shouldBe io.whozoss.agentos.sdk.spi.ToolGrantDecision.Neutral
        }
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
                "FACTORY__get_workstream",
                "FACTORY__list_workflows",
                "FACTORY__get_step_attempts",
                "FACTORY__get_blockers",
                "FACTORY__get_required_human_actions",
                "FACTORY__provision_environment",
                "FACTORY__start_workflow",
                "FACTORY__record_agent_result",
                "FACTORY__record_artifact",
                "FACTORY__submit_step_result",
                "FACTORY__ask_step_question",
                "FACTORY__request_human_decision",
                "FACTORY__request_transition",
                "FACTORY__transition_workflow",
                "FACTORY__publish_projection",
            )
    }
})
