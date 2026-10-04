package io.whozoss.agentos.plugins.factorybridge.tools

import com.fasterxml.jackson.module.kotlin.jacksonObjectMapper
import com.sun.net.httpserver.HttpServer
import io.kotest.core.spec.style.StringSpec
import io.kotest.matchers.shouldBe
import io.whozoss.agentos.sdk.caseEvent.CaseStatusEvent
import io.whozoss.agentos.sdk.caseFlow.CaseStatus
import io.whozoss.agentos.sdk.entity.EntityMetadata
import io.whozoss.agentos.sdk.tool.ToolContext
import io.whozoss.agentos.sdk.tool.ToolExecutionResult
import okhttp3.OkHttpClient
import java.net.InetSocketAddress
import java.util.UUID

/**
 * Phase 7 command tools: `FACTORY__request_agent_retry`, `FACTORY__interrupt_attempt`,
 * `FACTORY__propose_plan_change` — schema strictness, exact wire payload, trusted
 * identity headers and standardized success output (status / revision / reasonCode /
 * interactionId|proposalId / allowedActions).
 */
class FactoryCommandToolsTest : StringSpec({
    val mapper = jacksonObjectMapper()

    fun context(
        agent: String? = "WorkstreamAgent",
        actor: String? = "external-actor",
        caseCount: Int = 1,
    ): ToolContext {
        val namespaceId = UUID.randomUUID()
        return ToolContext(
            namespaceId,
            actor?.let { UUID.randomUUID() },
            actor,
            (1..caseCount).map {
                CaseStatusEvent(
                    metadata = EntityMetadata(),
                    namespaceId = namespaceId,
                    caseId = UUID.randomUUID(),
                    status = CaseStatus.PENDING,
                )
            },
            agent,
        )
    }

    class CapturedRequest {
        var method: String = ""
        var path: String = ""
        var body: String = ""
        var headers: Map<String, List<String>> = emptyMap()
    }

    fun fakeFactory(
        postStatus: Int,
        postResponse: String,
        actionsResponse: String = """{"data":{"allowedActions":[{"type":"retry","stepId":"build","expectedRevision":5}],"blockers":[]}}""",
        captured: CapturedRequest = CapturedRequest(),
    ): HttpServer {
        val server = HttpServer.create(InetSocketAddress("127.0.0.1", 0), 0)
        server.createContext("/") { exchange ->
            if (exchange.requestMethod == "POST") {
                captured.method = exchange.requestMethod
                captured.path = exchange.requestURI.toString()
                captured.body = exchange.requestBody.bufferedReader().readText()
                captured.headers = exchange.requestHeaders.mapKeys { it.key.lowercase() }
                val bytes = postResponse.toByteArray()
                exchange.sendResponseHeaders(postStatus, bytes.size.toLong())
                exchange.responseBody.use { it.write(bytes) }
            } else {
                exchange.requestBody.close()
                val bytes = actionsResponse.toByteArray()
                exchange.sendResponseHeaders(200, bytes.size.toLong())
                exchange.responseBody.use { it.write(bytes) }
            }
        }
        server.start()
        return server
    }

    // ----- schema strictness -------------------------------------------------

    "command tool schemas are strict and exclude trust attribution" {
        val tools =
            mapOf(
                "request_agent_retry" to
                    FactoryRequestAgentRetryTool("http://localhost", OkHttpClient(), mapper, "runtime"),
                "interrupt_attempt" to
                    FactoryInterruptAttemptTool("http://localhost", OkHttpClient(), mapper, "runtime"),
                "propose_plan_change" to
                    FactoryProposePlanChangeTool("http://localhost", OkHttpClient(), mapper, "runtime"),
            )
        tools["request_agent_retry"]!!.let { tool ->
            val schema = mapper.readTree(tool.inputSchema)
            schema.path("additionalProperties").asBoolean() shouldBe false
            schema.path("properties").fieldNames().asSequence().toSet() shouldBe
                setOf("workflowId", "stepId", "expectedRevision", "reasonCode", "idempotencyKey")
        }
        tools["interrupt_attempt"]!!.let { tool ->
            val schema = mapper.readTree(tool.inputSchema)
            schema.path("additionalProperties").asBoolean() shouldBe false
            schema.path("properties").fieldNames().asSequence().toSet() shouldBe
                setOf("workflowId", "attemptId", "expectedRevision", "reason", "idempotencyKey")
        }
        tools["propose_plan_change"]!!.let { tool ->
            val schema = mapper.readTree(tool.inputSchema)
            schema.path("additionalProperties").asBoolean() shouldBe false
            schema.path("properties").fieldNames().asSequence().toSet() shouldBe
                setOf(
                    "workflowId",
                    "expectedRevision",
                    "reasonCode",
                    "summary",
                    "proposalType",
                    "affectedStepIds",
                    "proposedDependencyChanges",
                    "proposedScopeChanges",
                    "evidenceRefs",
                    "idempotencyKey",
                )
            schema.path("properties").path("proposalType").path("enum").map { it.asText() } shouldBe
                listOf("RETRY", "PATH_SELECTION", "OPTIONAL_STEP", "DEPENDENCY", "SCOPE", "NEW_STEP", "CONTRACT_OR_ORACLE")
        }
        // No identity / trust field is ever model-authored.
        tools.values.forEach { tool ->
            val schema = mapper.readTree(tool.inputSchema)
            listOf("namespaceId", "agentId", "caseId", "actorId", "runtimeId", "token", "requestId").forEach {
                schema.path("properties").has(it) shouldBe false
            }
        }
    }

    // ----- request_agent_retry -----------------------------------------------

    "request_agent_retry posts exact body with trust headers and returns pending-human" {
        val captured = CapturedRequest()
        val server =
            fakeFactory(
                201,
                """{"data":{"workflowId":"wf-1","interaction":{"interactionId":"retry-1","workflowId":"wf-1","stepId":"build","interactionType":"retry","status":"waiting","revision":5}}}""",
                captured = captured,
            )
        try {
            val toolContext = context()
            val result =
                FactoryRequestAgentRetryTool("http://127.0.0.1:${server.address.port}", OkHttpClient(), mapper, "runtime-configured")
                    .execute(
                        FactoryRequestAgentRetryTool.Input("wf-1", "build", 5, "FLAKY_ORACLE", "retry-key-1"),
                        toolContext,
                    )
            result.success shouldBe true
            captured.path shouldBe "/api/factory/workflows/wf-1/retries"
            // Exact endpoint allowlist: the model-authored idempotencyKey is never sent.
            val sent = mapper.readTree(captured.body)
            sent.fieldNames().asSequence().toSet() shouldBe setOf("namespaceId", "stepId", "expectedRevision", "reasonCode")
            sent.path("namespaceId").asText() shouldBe toolContext.namespaceId.toString()
            sent.path("stepId").asText() shouldBe "build"
            sent.path("expectedRevision").asLong() shouldBe 5L
            sent.path("reasonCode").asText() shouldBe "FLAKY_ORACLE"
            // Trusted identity travels via headers, sourced from the ToolContext.
            captured.headers["x-factory-namespace-id"]?.single() shouldBe toolContext.namespaceId.toString()
            captured.headers["x-factory-runtime-id"]?.single() shouldBe "runtime-configured"
            captured.headers["x-factory-agent-id"]?.single() shouldBe "WorkstreamAgent"
            captured.headers["x-factory-case-id"]?.single() shouldBe toolContext.caseEvents.single().caseId.toString()
            captured.headers["x-factory-actor-id"]?.single() shouldBe "external-actor"
            // Standardized command output.
            val output = mapper.readTree(result.output)
            output.path("status").asText() shouldBe "pending-human"
            output.path("revision").asLong() shouldBe 5L
            output.path("reasonCode").asText() shouldBe "retry_requested"
            output.path("interactionId").asText() shouldBe "retry-1"
            output.path("proposalId").isNull shouldBe true
            output.path("allowedActions").isArray shouldBe true
            output.path("allowedActions").single().path("type").asText() shouldBe "retry"
            output.path("message").isTextual shouldBe true
        } finally {
            server.stop(0)
        }
    }

    "request_agent_retry fails closed without a complete trusted context" {
        val tool = FactoryRequestAgentRetryTool("http://localhost", OkHttpClient(), mapper, "runtime")
        val input = FactoryRequestAgentRetryTool.Input("wf-1", "build", 5, "FLAKY_ORACLE")
        tool.execute(input, context(caseCount = 0)).errorType shouldBe "CASE_CONTEXT_UNAVAILABLE"
        tool.execute(input, context(caseCount = 2)).errorType shouldBe "CASE_CONTEXT_UNAVAILABLE"
        tool.execute(input, context(agent = null)).errorType shouldBe "AGENT_CONTEXT_UNAVAILABLE"
        tool.execute(input, context(actor = null)).errorType shouldBe "USER_CONTEXT_UNAVAILABLE"
        tool.execute(null, context()).errorType shouldBe "INVALID_RETRY_REQUEST"
    }

    "request_agent_retry propagates factory rejection codes" {
        suspend fun call(
            status: Int,
            response: String,
        ): ToolExecutionResult {
            val server = fakeFactory(status, response)
            return try {
                FactoryRequestAgentRetryTool("http://127.0.0.1:${server.address.port}", OkHttpClient(), mapper, "runtime")
                    .execute(FactoryRequestAgentRetryTool.Input("wf-1", "build", 5, "FLAKY_ORACLE"), context())
            } finally {
                server.stop(0)
            }
        }
        call(409, """{"error":{"code":"REVISION_CONFLICT","message":"stale"}}""").errorType shouldBe "REVISION_CONFLICT"
        call(409, """{"error":{"code":"INTERACTION_STALE","message":"not blocked"}}""").errorType shouldBe "INTERACTION_STALE"
        call(400, """{"error":{"code":"INVALID_RETRY_REQUEST","message":"bad"}}""").errorType shouldBe "INVALID_RETRY_REQUEST"
        call(200, "bad").errorType shouldBe "MALFORMED_FACTORY_RESPONSE"
    }

    // ----- interrupt_attempt -------------------------------------------------

    "interrupt_attempt posts to the cancel endpoint and returns accepted" {
        val captured = CapturedRequest()
        val server =
            fakeFactory(
                200,
                """{"data":{"workflowId":"wf-1","attemptId":"att-1","stepId":"build","status":"interrupted","revision":6,"idempotent":false,"reconciledVerdict":"failed"}}""",
                captured = captured,
            )
        try {
            val toolContext = context()
            val result =
                FactoryInterruptAttemptTool("http://127.0.0.1:${server.address.port}", OkHttpClient(), mapper, "runtime-configured")
                    .execute(FactoryInterruptAttemptTool.Input("wf-1", "att-1", 6, "budget exceeded", "cancel-key-1"), toolContext)
            result.success shouldBe true
            captured.path shouldBe "/api/factory/workflows/wf-1/attempts/att-1/cancel"
            // attemptId travels in the path; the body stays within the allowlist.
            val sent = mapper.readTree(captured.body)
            sent.fieldNames().asSequence().toSet() shouldBe setOf("namespaceId", "expectedRevision", "reason")
            sent.path("namespaceId").asText() shouldBe toolContext.namespaceId.toString()
            sent.path("expectedRevision").asLong() shouldBe 6L
            sent.path("reason").asText() shouldBe "budget exceeded"
            captured.headers["x-factory-agent-id"]?.single() shouldBe "WorkstreamAgent"
            captured.headers["x-factory-actor-id"]?.single() shouldBe "external-actor"
            val output = mapper.readTree(result.output)
            output.path("status").asText() shouldBe "accepted"
            output.path("revision").asLong() shouldBe 6L
            output.path("reasonCode").asText() shouldBe "interrupted"
            output.path("attemptId").asText() shouldBe "att-1"
            output.path("stepId").asText() shouldBe "build"
            output.path("reconciledVerdict").asText() shouldBe "failed"
            output.path("interactionId").isNull shouldBe true
            output.path("proposalId").isNull shouldBe true
            output.path("allowedActions").isArray shouldBe true
        } finally {
            server.stop(0)
        }
    }

    "interrupt_attempt omits a blank reason and fails closed without context" {
        val captured = CapturedRequest()
        val server =
            fakeFactory(
                200,
                """{"data":{"workflowId":"wf-1","attemptId":"att-1","stepId":"build","status":"interrupted","revision":6,"idempotent":true,"reconciledVerdict":null}}""",
                captured = captured,
            )
        try {
            FactoryInterruptAttemptTool("http://127.0.0.1:${server.address.port}", OkHttpClient(), mapper, "runtime")
                .execute(FactoryInterruptAttemptTool.Input("wf-1", "att-1", 6), context())
            val sent = mapper.readTree(captured.body)
            sent.fieldNames().asSequence().toSet() shouldBe setOf("namespaceId", "expectedRevision")
        } finally {
            server.stop(0)
        }
        val tool = FactoryInterruptAttemptTool("http://localhost", OkHttpClient(), mapper, "runtime")
        val input = FactoryInterruptAttemptTool.Input("wf-1", "att-1", 6)
        tool.execute(input, context(caseCount = 0)).errorType shouldBe "CASE_CONTEXT_UNAVAILABLE"
        tool.execute(input, context(agent = null)).errorType shouldBe "AGENT_CONTEXT_UNAVAILABLE"
        tool.execute(input, context(actor = null)).errorType shouldBe "USER_CONTEXT_UNAVAILABLE"
        tool.execute(null, context()).errorType shouldBe "INVALID_REQUEST"
    }

    "interrupt_attempt propagates cancellation unavailability and revision conflicts" {
        suspend fun call(
            status: Int,
            response: String,
        ): ToolExecutionResult {
            val server = fakeFactory(status, response)
            return try {
                FactoryInterruptAttemptTool("http://127.0.0.1:${server.address.port}", OkHttpClient(), mapper, "runtime")
                    .execute(FactoryInterruptAttemptTool.Input("wf-1", "att-1", 6), context())
            } finally {
                server.stop(0)
            }
        }
        call(503, """{"error":{"code":"BRIDGE_CANCELLATION_UNAVAILABLE","message":"bridge off"}}""")
            .errorType shouldBe "BRIDGE_CANCELLATION_UNAVAILABLE"
        call(409, """{"error":{"code":"REVISION_CONFLICT","message":"stale"}}""").errorType shouldBe "REVISION_CONFLICT"
    }

    // ----- propose_plan_change -----------------------------------------------

    "propose_plan_change submits the proposal with injected namespace and returns pending-human" {
        val captured = CapturedRequest()
        val server =
            fakeFactory(
                201,
                """{"data":{"proposalId":"p-1","workflowId":"wf-1","namespaceId":"ns","workstreamId":"ws","reasonCode":"ADJUST_PLAN","summary":"add qa gate","proposalType":"DEPENDENCY","kind":"structural","recommendedVerdict":"GATE_REQUIRED","status":"GATE_REQUIRED","expectedRevision":3,"affectedStepIds":["build","qa"],"proposedDependencyChanges":[{"op":"ADD","fromStepId":"build","toStepId":"qa"}],"proposedScopeChanges":null,"evidenceRefs":[],"revision":1,"idempotent":false,"createdAt":"2026-01-01T00:00:00Z","updatedAt":"2026-01-01T00:00:00Z","decisions":[]}}""",
                captured = captured,
            )
        try {
            val toolContext = context()
            val result =
                FactoryProposePlanChangeTool("http://127.0.0.1:${server.address.port}", OkHttpClient(), mapper, "runtime-configured")
                    .execute(
                        FactoryProposePlanChangeTool.Input(
                            workflowId = "wf-1",
                            expectedRevision = 3,
                            reasonCode = "ADJUST_PLAN",
                            summary = "add qa gate",
                            proposalType = "DEPENDENCY",
                            affectedStepIds = listOf("build", "qa"),
                            proposedDependencyChanges =
                                listOf(FactoryProposePlanChangeTool.DependencyChange("ADD", "build", "qa")),
                            idempotencyKey = "proposal-key-1",
                        ),
                        toolContext,
                    )
            result.success shouldBe true
            captured.path shouldBe "/api/factory/plan-change-proposals"
            val sent = mapper.readTree(captured.body)
            sent.path("namespaceId").asText() shouldBe toolContext.namespaceId.toString()
            sent.path("workflowId").asText() shouldBe "wf-1"
            sent.path("expectedRevision").asLong() shouldBe 3L
            sent.path("reasonCode").asText() shouldBe "ADJUST_PLAN"
            sent.path("summary").asText() shouldBe "add qa gate"
            sent.path("proposalType").asText() shouldBe "DEPENDENCY"
            sent.path("idempotencyKey").asText() shouldBe "proposal-key-1"
            sent.path("proposedDependencyChanges").single().path("op").asText() shouldBe "ADD"
            captured.headers["x-factory-namespace-id"]?.single() shouldBe toolContext.namespaceId.toString()
            captured.headers["x-factory-actor-id"]?.single() shouldBe "external-actor"
            val output = mapper.readTree(result.output)
            output.path("status").asText() shouldBe "pending-human"
            output.path("revision").asLong() shouldBe 1L
            output.path("reasonCode").asText() shouldBe "GATE_REQUIRED"
            output.path("proposalId").asText() shouldBe "p-1"
            output.path("interactionId").isNull shouldBe true
            output.path("allowedActions").isArray shouldBe true
            output.path("idempotent").asBoolean() shouldBe false
        } finally {
            server.stop(0)
        }
    }

    "propose_plan_change maps verdicts and propagates factory codes" {
        suspend fun call(
            status: Int,
            response: String,
        ): ToolExecutionResult {
            val server = fakeFactory(status, response)
            return try {
                FactoryProposePlanChangeTool("http://127.0.0.1:${server.address.port}", OkHttpClient(), mapper, "runtime")
                    .execute(
                        FactoryProposePlanChangeTool.Input(
                            workflowId = "wf-1",
                            expectedRevision = 3,
                            reasonCode = "ADJUST_PLAN",
                            summary = "select path",
                            proposalType = "PATH_SELECTION",
                            idempotencyKey = "proposal-key-1",
                        ),
                        context(),
                    )
            } finally {
                server.stop(0)
            }
        }
        fun proposal(
            verdict: String,
            idempotent: Boolean = false,
        ) = """{"data":{"proposalId":"p-1","workflowId":"wf-1","status":"$verdict","recommendedVerdict":"$verdict","revision":2,"idempotent":$idempotent}}"""

        mapper.readTree(call(200, proposal("AUTO_APPLIED", idempotent = true)).output).path("status").asText() shouldBe "accepted"
        mapper.readTree(call(201, proposal("PENDING_VALIDATION")).output).path("status").asText() shouldBe "pending-human"
        mapper.readTree(call(201, proposal("REQUIRES_NEW_DEFINITION")).output).path("status").asText() shouldBe "pending-human"
        mapper.readTree(call(201, proposal("REJECTED")).output).path("status").asText() shouldBe "rejected"
        call(400, """{"error":{"code":"INVALID_PLAN_CHANGE_PROPOSAL","message":"bad"}}""").errorType shouldBe "INVALID_PLAN_CHANGE_PROPOSAL"
        call(400, """{"error":{"code":"INVALID_NAMESPACE_ID","message":"bad ns"}}""").errorType shouldBe "INVALID_NAMESPACE_ID"
        call(409, """{"error":{"code":"IDEMPOTENCY_KEY_COLLISION","message":"collision"}}""").errorType shouldBe "IDEMPOTENCY_KEY_COLLISION"
    }

    "propose_plan_change fails closed without a complete trusted context" {
        val tool = FactoryProposePlanChangeTool("http://localhost", OkHttpClient(), mapper, "runtime")
        val input =
            FactoryProposePlanChangeTool.Input(
                workflowId = "wf-1",
                expectedRevision = 3,
                reasonCode = "ADJUST_PLAN",
                summary = "add qa gate",
                proposalType = "DEPENDENCY",
                idempotencyKey = "proposal-key-1",
            )
        tool.execute(input, context(caseCount = 0)).errorType shouldBe "CASE_CONTEXT_UNAVAILABLE"
        tool.execute(input, context(agent = null)).errorType shouldBe "AGENT_CONTEXT_UNAVAILABLE"
        tool.execute(input, context(actor = null)).errorType shouldBe "USER_CONTEXT_UNAVAILABLE"
        tool.execute(null, context()).errorType shouldBe "INVALID_PLAN_CHANGE_PROPOSAL"
    }
})
