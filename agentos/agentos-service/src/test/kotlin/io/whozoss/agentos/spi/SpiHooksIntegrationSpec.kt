package io.whozoss.agentos.spi

import io.kotest.core.spec.style.StringSpec
import io.kotest.matchers.collections.shouldBeEmpty
import io.kotest.matchers.collections.shouldHaveSize
import io.kotest.matchers.collections.shouldNotBeEmpty
import io.kotest.matchers.shouldBe
import io.kotest.matchers.types.shouldBeInstanceOf
import io.whozoss.agentos.caseFlow.CaseRuntime
import io.whozoss.agentos.sdk.actor.Actor
import io.whozoss.agentos.sdk.actor.ActorRole
import io.whozoss.agentos.sdk.caseEvent.AgentSelectedEvent
import io.whozoss.agentos.sdk.caseEvent.AnswerEvent
import io.whozoss.agentos.sdk.caseEvent.CaseEvent
import io.whozoss.agentos.sdk.caseEvent.MessageContent
import io.whozoss.agentos.sdk.caseEvent.MessageEvent
import io.whozoss.agentos.sdk.caseEvent.QuestionEvent
import io.whozoss.agentos.sdk.caseEvent.WarnEvent
import io.whozoss.agentos.sdk.caseFlow.CaseStatus
import io.whozoss.agentos.sdk.spi.AnswerInterceptResult
import io.whozoss.agentos.sdk.spi.AnswerInterceptor
import io.whozoss.agentos.sdk.spi.CaseLifecycleObserver
import io.whozoss.agentos.sdk.spi.ExternalExecutionContextProvider
import io.whozoss.agentos.sdk.spi.ToolGrantDecision
import io.whozoss.agentos.sdk.spi.ToolGrantPolicy
import io.whozoss.agentos.sdk.tool.ToolContext
import java.time.Instant
import java.util.UUID

/**
 * Integration tests for the generic SPI hooks foundation.
 *
 * Verifies that the four SPI extension points default to safe no-op behavior and that
 * [CaseRuntime] invokes the answer interceptor and lifecycle observer at the right time,
 * while leaving existing behavior untouched when no hook is registered.
 */
class SpiHooksIntegrationSpec : StringSpec({

    val namespaceId: UUID = UUID.randomUUID()
    val agentId: UUID = UUID.nameUUIDFromBytes("test-agent".toByteArray())
    val userActor = Actor(id = UUID.randomUUID().toString(), displayName = "Alice", role = ActorRole.USER)

    fun questionEvent(caseId: UUID) =
        QuestionEvent(
            namespaceId = namespaceId,
            caseId = caseId,
            agentId = agentId,
            agentName = "test-agent",
            question = "Approve?",
            userId = UUID.fromString(userActor.id),
            timestamp = Instant.EPOCH.plusSeconds(3),
        )

    fun buildRuntime(
        caseId: UUID,
        savedEvents: MutableList<CaseEvent>,
        answerInterceptors: List<AnswerInterceptor> = emptyList(),
        lifecycleObservers: List<CaseLifecycleObserver> = emptyList(),
    ): CaseRuntime =
        CaseRuntime(
            id = caseId,
            namespaceId = namespaceId,
            caseCreatedAt = Instant.EPOCH,
            updateStatusCallback = { _, _ -> },
            storeEvent = { event -> savedEvents.add(event); event },
            selectAgent = { _, _ ->
                listOf(
                    AgentSelectedEvent(
                        namespaceId = namespaceId,
                        caseId = caseId,
                        agentId = agentId,
                        agentName = "test-agent",
                    ),
                )
            },
            isAgentAuthorized = { _, _ -> true },
            runAgent = { _, _, _, _, _ -> },
            answerInterceptors = answerInterceptors,
            lifecycleObservers = lifecycleObservers,
        )

    fun runtimeWithPendingQuestion(
        caseId: UUID,
        savedEvents: MutableList<CaseEvent>,
        answerInterceptors: List<AnswerInterceptor> = emptyList(),
        lifecycleObservers: List<CaseLifecycleObserver> = emptyList(),
    ): Pair<CaseRuntime, QuestionEvent> {
        val runtime = buildRuntime(caseId, savedEvents, answerInterceptors, lifecycleObservers)
        val question = questionEvent(caseId)
        runtime.pushEvents(
            listOf(
                MessageEvent(
                    namespaceId = namespaceId,
                    caseId = caseId,
                    actor = userActor,
                    content = listOf(MessageContent.Text("start")),
                    timestamp = Instant.EPOCH.plusSeconds(1),
                ),
                question,
            ),
        )
        return runtime to question
    }

    // -------------------------------------------------------------------------
    // SDK defaults: every SPI hook is a safe no-op out of the box
    // -------------------------------------------------------------------------

    "SDK SPI hooks default to neutral no-op behavior" {
        val question = questionEvent(UUID.randomUUID())

        val interceptor = object : AnswerInterceptor {}
        interceptor.interceptAnswer(UUID.randomUUID(), question, "yes", userActor) shouldBe AnswerInterceptResult.Accept

        val observer = object : CaseLifecycleObserver {}
        observer.onStatusChanged(UUID.randomUUID(), CaseStatus.PENDING, CaseStatus.RUNNING)
        observer.onEventStored(UUID.randomUUID(), question)

        val provider = object : ExternalExecutionContextProvider {}
        provider.provideExecutionContext(UUID.randomUUID(), namespaceId, null) shouldBe emptyMap()

        val toolContext =
            ToolContext(
                namespaceId = namespaceId,
                userId = null,
                userExternalId = null,
                caseEvents = emptyList(),
            )
        val policy = object : ToolGrantPolicy {}
        policy.evaluateToolGrant("test-agent", "SOME__tool", toolContext) shouldBe ToolGrantDecision.Neutral
    }

    // -------------------------------------------------------------------------
    // AnswerInterceptor
    // -------------------------------------------------------------------------

    "no AnswerInterceptor registered: answer behavior is unchanged" {
        val caseId = UUID.randomUUID()
        val savedEvents = mutableListOf<CaseEvent>()
        val (runtime, question) = runtimeWithPendingQuestion(caseId, savedEvents)

        runtime.addUserMessage(userActor, listOf(MessageContent.Text("Approve")), answerToEventId = question.id)

        savedEvents.filterIsInstance<AnswerEvent>() shouldHaveSize 1
        savedEvents.filterIsInstance<WarnEvent>().shouldBeEmpty()
    }

    "accepting AnswerInterceptor: AnswerEvent is created" {
        val caseId = UUID.randomUUID()
        val savedEvents = mutableListOf<CaseEvent>()
        val seen = mutableListOf<Triple<UUID, QuestionEvent, String>>()
        val interceptor =
            object : AnswerInterceptor {
                override fun interceptAnswer(
                    caseId: UUID,
                    questionEvent: QuestionEvent,
                    answerText: String,
                    actor: Actor,
                ): AnswerInterceptResult {
                    seen += Triple(caseId, questionEvent, answerText)
                    return AnswerInterceptResult.Accept
                }
            }

        val (runtime, question) = runtimeWithPendingQuestion(caseId, savedEvents, answerInterceptors = listOf(interceptor))

        runtime.addUserMessage(userActor, listOf(MessageContent.Text("Approve")), answerToEventId = question.id)

        seen shouldHaveSize 1
        seen[0].first shouldBe caseId
        seen[0].second.id shouldBe question.id
        seen[0].third shouldBe "Approve"
        savedEvents.filterIsInstance<AnswerEvent>() shouldHaveSize 1
    }

    "rejecting AnswerInterceptor: no AnswerEvent, WarnEvent surfaced" {
        val caseId = UUID.randomUUID()
        val savedEvents = mutableListOf<CaseEvent>()
        val interceptor =
            object : AnswerInterceptor {
                override fun interceptAnswer(
                    caseId: UUID,
                    questionEvent: QuestionEvent,
                    answerText: String,
                    actor: Actor,
                ): AnswerInterceptResult = AnswerInterceptResult.Reject("not allowed yet")
            }

        val (runtime, question) = runtimeWithPendingQuestion(caseId, savedEvents, answerInterceptors = listOf(interceptor))

        runtime.addUserMessage(userActor, listOf(MessageContent.Text("Approve")), answerToEventId = question.id)

        savedEvents.filterIsInstance<AnswerEvent>().shouldBeEmpty()
        val warns = savedEvents.filterIsInstance<WarnEvent>()
        warns shouldHaveSize 1
        warns[0].message shouldBe "Answer rejected: not allowed yet. Please try again."
    }

    "throwing AnswerInterceptor fails open: AnswerEvent is still created" {
        val caseId = UUID.randomUUID()
        val savedEvents = mutableListOf<CaseEvent>()
        val interceptor =
            object : AnswerInterceptor {
                override fun interceptAnswer(
                    caseId: UUID,
                    questionEvent: QuestionEvent,
                    answerText: String,
                    actor: Actor,
                ): AnswerInterceptResult = throw IllegalStateException("boom")
            }

        val (runtime, question) = runtimeWithPendingQuestion(caseId, savedEvents, answerInterceptors = listOf(interceptor))

        runtime.addUserMessage(userActor, listOf(MessageContent.Text("Approve")), answerToEventId = question.id)

        savedEvents.filterIsInstance<AnswerEvent>() shouldHaveSize 1
    }

    // -------------------------------------------------------------------------
    // CaseLifecycleObserver
    // -------------------------------------------------------------------------

    "CaseLifecycleObserver is notified when events are stored" {
        val caseId = UUID.randomUUID()
        val savedEvents = mutableListOf<CaseEvent>()
        val observed = mutableListOf<Pair<UUID, CaseEvent>>()
        val observer =
            object : CaseLifecycleObserver {
                override fun onEventStored(caseId: UUID, event: CaseEvent) {
                    observed += caseId to event
                }
            }

        val runtime = buildRuntime(caseId, savedEvents, lifecycleObservers = listOf(observer))
        runtime.addUserMessage(userActor, listOf(MessageContent.Text("hello")))

        observed.map { it.first }.all { it == caseId } shouldBe true
        observed.map { it.second } shouldBe savedEvents
        observed.map { it.second }.any { it is MessageEvent } shouldBe true
        observed.map { it.second }.any { it is AgentSelectedEvent } shouldBe true
    }

    "throwing CaseLifecycleObserver does not break event storage" {
        val caseId = UUID.randomUUID()
        val savedEvents = mutableListOf<CaseEvent>()
        val observer =
            object : CaseLifecycleObserver {
                override fun onEventStored(caseId: UUID, event: CaseEvent): Unit = throw IllegalStateException("boom")
            }

        val runtime = buildRuntime(caseId, savedEvents, lifecycleObservers = listOf(observer))
        runtime.addUserMessage(userActor, listOf(MessageContent.Text("hello")))

        savedEvents.shouldNotBeEmpty()
        savedEvents.first().shouldBeInstanceOf<MessageEvent>()
    }
})
