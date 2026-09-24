package io.whozoss.agentos.factory

import com.fasterxml.jackson.module.kotlin.jacksonObjectMapper
import com.sun.net.httpserver.HttpServer
import io.kotest.core.spec.style.StringSpec
import io.kotest.matchers.shouldBe
import io.kotest.matchers.types.shouldBeInstanceOf
import io.whozoss.agentos.agent.AgentInterrupt
import io.whozoss.agentos.sdk.caseEvent.CaseStatusEvent
import io.whozoss.agentos.sdk.caseEvent.QuestionType
import io.whozoss.agentos.sdk.caseFlow.CaseStatus
import io.whozoss.agentos.sdk.entity.EntityMetadata
import io.whozoss.agentos.sdk.tool.ToolContext
import okhttp3.OkHttpClient
import java.net.InetSocketAddress
import java.util.UUID

class FactoryRequestHumanDecisionToolSpec : StringSpec({
    val mapper = jacksonObjectMapper()

    fun context(): ToolContext {
        val namespaceId = UUID.randomUUID()
        return ToolContext(
            namespaceId,
            UUID.randomUUID(),
            "external-user",
            listOf(CaseStatusEvent(EntityMetadata(), namespaceId, UUID.randomUUID(), status = CaseStatus.PENDING)),
            "ProductEngineer",
        )
    }

    val input = FactoryRequestHumanDecisionTool.Input(
        "wf-1", "intent-checkpoint", 3, "Approve the intent?",
        listOf(
            FactoryRequestHumanDecisionTool.Action("approve", "Approve", "completed"),
            FactoryRequestHumanDecisionTool.Action("reject", "Reject", "failed"),
        ),
        "human-1",
    )

    "strict schema exposes no trust attribution" {
        val schema = mapper.readTree(FactoryRequestHumanDecisionTool("http://localhost", OkHttpClient(), mapper, "runtime").inputSchema)
        schema.path("properties").fieldNames().asSequence().toSet() shouldBe setOf(
            "workflowId", "stepId", "expectedRevision", "prompt", "actions", "idempotencyKey",
        )
        schema.path("additionalProperties").asBoolean() shouldBe false
    }

    "sends exact route payload and trusted headers" {
        var path = ""
        var body = ""
        var headers = emptyMap<String, String>()
        val server = HttpServer.create(InetSocketAddress("127.0.0.1", 0), 0)
        server.createContext("/") { exchange ->
            path = exchange.requestURI.toString()
            body = exchange.requestBody.bufferedReader().readText()
            headers = listOf("x-factory-namespace-id", "x-factory-runtime-id", "x-factory-agent-id", "x-factory-case-id")
                .associateWith { exchange.requestHeaders.getFirst(it) }
            val bytes = """{"data":{"interaction":{"interactionId":"gate-1","revision":4}}}""".toByteArray()
            exchange.sendResponseHeaders(200, bytes.size.toLong())
            exchange.responseBody.use { it.write(bytes) }
        }
        server.start()
        try {
            val toolContext = context()
            try {
                FactoryRequestHumanDecisionTool(
                    "http://127.0.0.1:${server.address.port}", OkHttpClient(), mapper, "agentos-primary",
                ).execute(input, toolContext)
            } catch (_: AgentInterrupt.AwaitAnswer) {
                // expected: successful open throws AwaitAnswer to suspend the agent
            }
            path shouldBe "/api/factory/workflows/wf-1/interactions"
            val sent = mapper.readTree(body)
            sent.fieldNames().asSequence().toSet() shouldBe setOf(
                "stepId", "expectedRevision", "kind", "prompt", "actions", "idempotencyKey",
            )
            headers["x-factory-namespace-id"] shouldBe toolContext.namespaceId.toString()
            headers["x-factory-runtime-id"] shouldBe "agentos-primary"
            headers["x-factory-agent-id"] shouldBe "ProductEngineer"
            headers["x-factory-case-id"] shouldBe toolContext.caseEvents.single().caseId.toString()
        } finally {
            server.stop(0)
        }
    }

    "rejects invalid actions before transport" {
        val invalid = input.copy(actions = listOf(FactoryRequestHumanDecisionTool.Action("approve", "Approve", "completed")))
        FactoryRequestHumanDecisionTool("http://localhost", OkHttpClient(), mapper, "runtime")
            .execute(invalid, context()).errorType shouldBe "INVALID_INTERACTION"
    }

    "successful open throws AwaitAnswer with correct FactoryCheckpointRef" {
        val server = HttpServer.create(InetSocketAddress("127.0.0.1", 0), 0)
        server.createContext("/") { exchange ->
            // Factory returns interaction id and revision
            val bytes = """{"data":{"interaction":{"interactionId":"gate-42","revision":7}}}""".toByteArray()
            exchange.sendResponseHeaders(200, bytes.size.toLong())
            exchange.responseBody.use { it.write(bytes) }
        }
        server.start()
        try {
            val tool = FactoryRequestHumanDecisionTool(
                "http://127.0.0.1:${server.address.port}", OkHttpClient(), mapper, "runtime",
            )
            val toolContext = context()
            var caught: AgentInterrupt.AwaitAnswer? = null
            try {
                tool.execute(input, toolContext)
            } catch (e: AgentInterrupt.AwaitAnswer) {
                caught = e
            }
            caught.shouldBeInstanceOf<AgentInterrupt.AwaitAnswer>()
            caught.question shouldBe input.prompt
            caught.questionType shouldBe QuestionType.SINGLE_CHOICE
            caught.options shouldBe listOf("Approve", "Reject")
            caught.factoryCheckpoint?.workflowId shouldBe "wf-1"
            caught.factoryCheckpoint?.interactionId shouldBe "gate-42"
            caught.factoryCheckpoint?.interactionRevision shouldBe 7L
        } finally { server.stop(0) }
    }

    "malformed response missing interaction id does not throw AwaitAnswer" {
        val server = HttpServer.create(InetSocketAddress("127.0.0.1", 0), 0)
        server.createContext("/") { exchange ->
            val bytes = """{"data":{}}""".toByteArray()
            exchange.sendResponseHeaders(200, bytes.size.toLong())
            exchange.responseBody.use { it.write(bytes) }
        }
        server.start()
        try {
            val tool = FactoryRequestHumanDecisionTool(
                "http://127.0.0.1:${server.address.port}", OkHttpClient(), mapper, "runtime",
            )
            // Should NOT throw AwaitAnswer — returns error result instead
            var awaitThrown = false
            val result = try {
                tool.execute(input, context())
            } catch (e: AgentInterrupt.AwaitAnswer) {
                awaitThrown = true
                null
            }
            awaitThrown shouldBe false
            result?.success shouldBe false
        } finally { server.stop(0) }
    }

    "plugin and grants expose human decision and both transition names" {
        val plugin = FactoryToolPlugin(mapper, "http://localhost", "runtime")
        plugin.provideTools(null, null, context()).map { it.name }.toSet().containsAll(
            setOf("FACTORY__request_human_decision", "FACTORY__request_transition", "FACTORY__transition_workflow"),
        ) shouldBe true
        val grant = FactoryToolGrantService(plugin)
        grant.grantTools(context(), mapOf("FACTORY" to listOf("request_human_decision", "transition_workflow"))).map { it.name } shouldBe
            listOf("FACTORY__request_human_decision", "FACTORY__transition_workflow")
        grant.grantTools(context(), mapOf("FACTORY" to listOf("FACTORY__request_transition"))).map { it.name } shouldBe
            listOf("FACTORY__request_transition")
    }
})
