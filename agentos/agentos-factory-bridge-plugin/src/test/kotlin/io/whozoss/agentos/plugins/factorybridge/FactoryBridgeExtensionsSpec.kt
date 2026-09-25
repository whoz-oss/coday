package io.whozoss.agentos.plugins.factorybridge

import com.fasterxml.jackson.module.kotlin.jacksonObjectMapper
import com.sun.net.httpserver.HttpServer
import io.kotest.core.spec.style.StringSpec
import io.kotest.matchers.shouldBe
import io.kotest.matchers.types.shouldBeInstanceOf
import io.whozoss.agentos.sdk.actor.Actor
import io.whozoss.agentos.sdk.actor.ActorRole
import io.whozoss.agentos.sdk.caseEvent.CaseStatusEvent
import io.whozoss.agentos.sdk.caseEvent.QuestionEvent
import io.whozoss.agentos.sdk.caseFlow.CaseStatus
import io.whozoss.agentos.sdk.entity.EntityMetadata
import io.whozoss.agentos.sdk.spi.AnswerInterceptResult
import io.whozoss.agentos.sdk.spi.ToolGrantDecision
import io.whozoss.agentos.sdk.tool.ToolContext
import java.net.InetSocketAddress
import java.time.Instant
import java.util.UUID

class FactoryBridgeExtensionsSpec : StringSpec({
    val mapper = jacksonObjectMapper()

    fun question(
        namespaceId: UUID,
        caseId: UUID,
    ) = QuestionEvent(
        metadata = EntityMetadata(),
        namespaceId = namespaceId,
        caseId = caseId,
        agentId = UUID.randomUUID(),
        agentName = "ProductEngineer",
        question = "Approve?",
    )

    "answer interceptor accepts questions without a checkpoint without performing any HTTP call" {
        val services = FactoryTestFixtures.services("http://127.0.0.1:1")
        val interceptor = FactoryAnswerInterceptor { services }
        val namespaceId = UUID.randomUUID()
        val result =
            interceptor.interceptAnswer(
                UUID.randomUUID(),
                question(namespaceId, UUID.randomUUID()),
                "approve",
                Actor("user-1", "User", ActorRole.USER),
            )
        result shouldBe AnswerInterceptResult.Accept
    }

    "answer interceptor accepts when the Factory accepts the decision" {
        val server = HttpServer.create(InetSocketAddress("127.0.0.1", 0), 0)
        server.createContext("/") { exchange ->
            exchange.requestBody.close()
            val bytes = """{"data":{"ok":true}}""".toByteArray()
            exchange.sendResponseHeaders(200, bytes.size.toLong())
            exchange.responseBody.use { it.write(bytes) }
        }
        server.start()
        try {
            val services = FactoryTestFixtures.services("http://127.0.0.1:${server.address.port}")
            val interceptor = FactoryAnswerInterceptor { services }
            val namespaceId = UUID.randomUUID()
            val caseId = UUID.randomUUID()
            services.pendingCheckpoints[caseId] = FactoryCheckpointRef("wf-1", "gate-1", 4L)
            val result =
                interceptor.interceptAnswer(
                    caseId,
                    question(namespaceId, caseId),
                    "approve",
                    Actor("user-1", "User", ActorRole.USER),
                )
            result shouldBe AnswerInterceptResult.Accept
        } finally {
            server.stop(0)
        }
    }

    "answer interceptor rejects with the Factory reason on a 409" {
        val server = HttpServer.create(InetSocketAddress("127.0.0.1", 0), 0)
        server.createContext("/") { exchange ->
            exchange.requestBody.close()
            val bytes = """{"error":{"code":"REVISION_CONFLICT","message":"Stale revision"}}""".toByteArray()
            exchange.sendResponseHeaders(409, bytes.size.toLong())
            exchange.responseBody.use { it.write(bytes) }
        }
        server.start()
        try {
            val services = FactoryTestFixtures.services("http://127.0.0.1:${server.address.port}")
            val interceptor = FactoryAnswerInterceptor { services }
            val namespaceId = UUID.randomUUID()
            val caseId = UUID.randomUUID()
            services.pendingCheckpoints[caseId] = FactoryCheckpointRef("wf-1", "gate-1", 4L)
            val result =
                interceptor.interceptAnswer(
                    caseId,
                    question(namespaceId, caseId),
                    "approve",
                    Actor("user-1", "User", ActorRole.USER),
                )
            result.shouldBeInstanceOf<AnswerInterceptResult.Reject>()
            (result as AnswerInterceptResult.Reject).reason shouldBe "Stale revision"
        } finally {
            server.stop(0)
        }
    }

    "external execution context provider exposes the active binding and nothing otherwise" {
        val services = FactoryTestFixtures.services()
        val provider = FactoryExternalExecutionContextProvider { services }
        val namespaceId = UUID.randomUUID()
        val caseId = UUID.randomUUID()

        provider.provideExecutionContext(caseId, namespaceId, null) shouldBe emptyMap()

        services.stepResultBindings.bind(
            FactoryStepResultBinding(
                caseId,
                namespaceId,
                "Worker",
                "attempt",
                "runtime",
                "secret-token-value-with-sufficient-length",
                Instant.now().plusSeconds(60),
            ),
        )
        val context = provider.provideExecutionContext(caseId, namespaceId, null)
        context.containsKey("capabilityToken") shouldBe true
        context.containsKey("attemptId") shouldBe true
        context["runtimeId"] shouldBe "runtime"
    }

    "lifecycle observer invalidates the binding when the case reaches a terminal status" {
        val services = FactoryTestFixtures.services()
        val observer = FactoryCaseLifecycleObserver { services }
        val namespaceId = UUID.randomUUID()
        val caseId = UUID.randomUUID()
        services.stepResultBindings.bind(
            FactoryStepResultBinding(
                caseId,
                namespaceId,
                "Worker",
                "attempt",
                "runtime",
                "secret-token-value-with-sufficient-length",
                Instant.now().plusSeconds(60),
            ),
        )
        services.stepResultBindings.contains(caseId) shouldBe true

        observer.onStatusChanged(caseId, CaseStatus.IDLE, CaseStatus.RUNNING)
        services.stepResultBindings.contains(caseId) shouldBe true

        observer.onStatusChanged(caseId, CaseStatus.RUNNING, CaseStatus.KILLED)
        services.stepResultBindings.contains(caseId) shouldBe false
    }

    "lifecycle observer tolerates event observations" {
        val observer = FactoryCaseLifecycleObserver { FactoryTestFixtures.services() }
        val namespaceId = UUID.randomUUID()
        val caseId = UUID.randomUUID()
        observer.onEventStored(caseId, CaseStatusEvent(metadata = EntityMetadata(), namespaceId = namespaceId, caseId = caseId, status = CaseStatus.RUNNING))
    }

    "tool grant policy denies the step-result tool without an active binding and is neutral once bound" {
        val services = FactoryTestFixtures.services()
        val policy = FactoryToolGrantPolicy { services }
        val namespaceId = UUID.randomUUID()
        val caseId = UUID.randomUUID()
        val context =
            ToolContext(
                namespaceId,
                UUID.randomUUID(),
                "user",
                listOf(CaseStatusEvent(metadata = EntityMetadata(), namespaceId = namespaceId, caseId = caseId, status = CaseStatus.RUNNING)),
                "Worker",
            )

        val denied = policy.evaluateToolGrant("Worker", "FACTORY__submit_step_result", context)
        denied.shouldBeInstanceOf<ToolGrantDecision.Deny>()

        policy.evaluateToolGrant("Worker", "FACTORY__get_workflow", context) shouldBe ToolGrantDecision.Neutral

        services.stepResultBindings.bind(
            FactoryStepResultBinding(
                caseId,
                namespaceId,
                "Worker",
                "attempt",
                "runtime",
                "secret-token-value-with-sufficient-length",
                Instant.now().plusSeconds(60),
            ),
        )
        policy.evaluateToolGrant("Worker", "FACTORY__submit_step_result", context) shouldBe ToolGrantDecision.Neutral
    }
})
