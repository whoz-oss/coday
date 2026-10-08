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

    "workstream plugin exposes exactly the six reads plus two boundary commands and they stay Neutral in the grant policy" {
        val context = ToolContext(UUID.randomUUID(), null, null, emptyList(), "agent")
        val readSuffixes =
            listOf("get_workstream", "list_workflows", "get_workflow", "get_step_attempts", "get_blockers", "get_required_human_actions")
        val commandSuffixes = listOf("start_workflow", "request_agent_retry")
        // The workstream plugin provides exactly the six reads plus the two boundary request
        // commands — no worker or deprecated command tool leaks in.
        FactoryTestFixtures.workstreamTools().map { it.name }.toSet() shouldBe
            (readSuffixes + commandSuffixes).map { "FACTORY_WORKSTREAM__$it" }.toSet()
        // All eight Workstream tools are intentionally not capability-gated: the policy stays
        // Neutral (retry authority stays with the Factory, pending-human under revision fence).
        val policy = FactoryToolGrantPolicy { FactoryTestFixtures.services() }
        (readSuffixes + commandSuffixes).forEach { suffix ->
            policy.evaluateToolGrant("FACTORY_WORKSTREAM__$suffix", context) shouldBe io.whozoss.agentos.sdk.spi.ToolGrantDecision.Neutral
        }
        // The worker tools live exclusively on the worker plugin.
        FactoryTestFixtures.workerTools().map { it.name }.toSet() shouldBe
            setOf("FACTORY_WORKER__submit_step_result")
    }

    "workstream and worker plugins keep strictly separated surfaces and expose no deprecated command tool" {
        val workstreamNames = FactoryTestFixtures.workstreamTools().map { it.name }
        val workerNames = FactoryTestFixtures.workerTools().map { it.name }
        // Strict separation: no FACTORY_WORKER__* tool on the workstream plugin, and no
        // FACTORY_WORKSTREAM__* tool on the worker plugin.
        workstreamNames.none { it.startsWith("FACTORY_WORKER__") } shouldBe true
        workerNames.none { it.startsWith("FACTORY_WORKSTREAM__") } shouldBe true
        // The deprecated command/transition tools (kept @Deprecated with their legacy
        // FACTORY__* names) are exposed by neither plugin.
        val exposed = (workstreamNames + workerNames).toSet()
        listOf(
            "FACTORY__transition_workflow",
            "FACTORY__request_transition",
            "FACTORY__interrupt_attempt",
            "FACTORY__request_human_decision",
            "FACTORY__propose_plan_change",
            "FACTORY__publish_projection",
            "FACTORY__provision_environment",
            "FACTORY__record_agent_result",
            "FACTORY__record_artifact",
        ).forEach { exposed.contains(it) shouldBe false }
    }

    "workstream tool plugin exposes exactly the eight Workstream tools and registers in the catalog" {
        val plugin = FactoryWorkstreamToolPlugin { FactoryTestFixtures.services() }
        plugin.integrationType shouldBe "FACTORY_WORKSTREAM"
        // Non-null empty-object schema: the plugin appears in the standard integration catalog.
        plugin.configSchema.path("type").asText() shouldBe "object"
        plugin.configSchema.path("properties").size() shouldBe 0
        plugin
            .provideTools(null, null, ToolContext(UUID.randomUUID(), null, null, emptyList()))
            .map { it.name }
            .shouldContainExactly(
                "FACTORY_WORKSTREAM__get_workflow",
                "FACTORY_WORKSTREAM__get_workstream",
                "FACTORY_WORKSTREAM__list_workflows",
                "FACTORY_WORKSTREAM__get_step_attempts",
                "FACTORY_WORKSTREAM__get_blockers",
                "FACTORY_WORKSTREAM__get_required_human_actions",
                "FACTORY_WORKSTREAM__start_workflow",
                "FACTORY_WORKSTREAM__request_agent_retry",
            )
    }

    "worker tool plugin exposes only the terminal result tool and registers in the catalog" {
        val plugin = FactoryWorkerToolPlugin { FactoryTestFixtures.services() }
        plugin.integrationType shouldBe "FACTORY_WORKER"
        plugin.configSchema.path("type").asText() shouldBe "object"
        plugin.configSchema.path("properties").size() shouldBe 0
        plugin
            .provideTools(null, null, ToolContext(UUID.randomUUID(), null, null, emptyList()))
            .map { it.name }
            .shouldContainExactly("FACTORY_WORKER__submit_step_result")
    }
})
