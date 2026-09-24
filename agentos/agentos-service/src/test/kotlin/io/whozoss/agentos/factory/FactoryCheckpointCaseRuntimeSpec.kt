package io.whozoss.agentos.factory

import io.kotest.core.spec.style.StringSpec
import io.kotest.matchers.shouldBe
import io.kotest.matchers.types.shouldBeInstanceOf
import io.whozoss.agentos.agent.AgentInterrupt
import io.whozoss.agentos.caseFlow.CaseRuntime
import io.whozoss.agentos.sdk.actor.Actor
import io.whozoss.agentos.sdk.actor.ActorRole
import io.whozoss.agentos.sdk.caseEvent.AgentFinishedEvent
import io.whozoss.agentos.sdk.caseEvent.AgentSelectedEvent
import io.whozoss.agentos.sdk.caseEvent.AnswerEvent
import io.whozoss.agentos.sdk.caseEvent.CaseEvent
import io.whozoss.agentos.sdk.caseEvent.FactoryCheckpointRef
import io.whozoss.agentos.sdk.caseEvent.MessageContent
import io.whozoss.agentos.sdk.caseEvent.MessageEvent
import io.whozoss.agentos.sdk.caseEvent.QuestionEvent
import io.whozoss.agentos.sdk.caseEvent.WarnEvent
import io.whozoss.agentos.sdk.caseFlow.CaseStatus
import java.time.Instant
import java.util.UUID

/**
 * Tests for the Factory checkpoint gate inside [CaseRuntime.addUserMessage].
 *
 * All tests use a real [CaseRuntime] with a stub [FactoryCheckpointClient] so no
 * HTTP traffic is produced. The stub is constructed via a subclass that overrides
 * [FactoryCheckpointClient.submitDecision] through a lambda captured at construction.
 */
class FactoryCheckpointCaseRuntimeSpec : StringSpec({
    val namespaceId: UUID = UUID.randomUUID()
    val agentId: UUID = UUID.nameUUIDFromBytes("test-agent".toByteArray())
    val userActor = Actor(id = UUID.randomUUID().toString(), displayName = "Alice", role = ActorRole.USER)
    val ref = FactoryCheckpointRef("wf-1", "gate-1", 4L)

    fun questionEvent(
        caseId: UUID,
        checkpoint: FactoryCheckpointRef? = ref,
    ) = QuestionEvent(
        namespaceId = namespaceId,
        caseId = caseId,
        agentId = agentId,
        agentName = "test-agent",
        question = "Approve or reject?",
        options = listOf("Approve", "Reject"),
        factoryCheckpoint = checkpoint,
        userId = UUID.fromString(userActor.id),
        timestamp = Instant.EPOCH.plusSeconds(3),
    )

    fun buildRuntime(
        caseId: UUID,
        savedEvents: MutableList<CaseEvent>,
        factoryResult: Result<Unit>,
        factoryCallCount: MutableList<String> = mutableListOf(),
    ): Pair<CaseRuntime, FactoryCheckpointClient> {
        // Minimal stub: captures call args and returns the configured result.
        val stubClient = object : FactoryCheckpointClient(
            "http://stub",
            okhttp3.OkHttpClient(),
            com.fasterxml.jackson.module.kotlin.jacksonObjectMapper(),
        ) {
            override suspend fun submitDecision(
                ref: FactoryCheckpointRef,
                decision: String,
                caseId: String,
                actorId: String,
            ): Result<Unit> {
                factoryCallCount += decision
                return factoryResult
            }
        }

        val runtime = CaseRuntime(
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
            runAgent = { _, _, _, _, _ ->
                savedEvents.add(
                    AgentFinishedEvent(
                        namespaceId = namespaceId,
                        caseId = caseId,
                        agentId = agentId,
                        agentName = "test-agent",
                    ),
                )
            },
            factoryCheckpointClient = stubClient,
        )
        return runtime to stubClient
    }

    // Pre-populate a runtime with a question that has a Factory checkpoint.
    // Returns the runtime AND the exact QuestionEvent instance pushed into it.
    fun runtimeWithPendingQuestion(
        caseId: UUID,
        savedEvents: MutableList<CaseEvent>,
        factoryResult: Result<Unit>,
        factoryCallCount: MutableList<String> = mutableListOf(),
    ): Pair<CaseRuntime, QuestionEvent> {
        val (runtime) = buildRuntime(caseId, savedEvents, factoryResult, factoryCallCount)
        val question = questionEvent(caseId)
        // Push existing events: a user message + agent finished + question (agent suspended)
        runtime.pushEvents(
            listOf(
                MessageEvent(
                    namespaceId = namespaceId,
                    caseId = caseId,
                    actor = userActor,
                    content = listOf(MessageContent.Text("start")),
                    timestamp = Instant.EPOCH.plusSeconds(1),
                ),
                AgentFinishedEvent(
                    namespaceId = namespaceId,
                    caseId = caseId,
                    agentId = agentId,
                    agentName = "test-agent",
                    timestamp = Instant.EPOCH.plusSeconds(2),
                ),
                question,
            ),
        )
        return runtime to question
    }

    "Factory accepted: AnswerEvent is created and agent resumes" {
        val caseId = UUID.randomUUID()
        val savedEvents = mutableListOf<CaseEvent>()
        val factoryCallCount = mutableListOf<String>()

        val (runtime, question) = runtimeWithPendingQuestion(caseId, savedEvents, Result.success(Unit), factoryCallCount)

        runtime.addUserMessage(
            actor = userActor,
            content = listOf(MessageContent.Text("Approve")),
            answerToEventId = question.id,
        )

        // Factory was called once with the user's decision
        factoryCallCount shouldBe listOf("Approve")
        // AnswerEvent was persisted (in savedEvents via storeEvent)
        savedEvents.filterIsInstance<AnswerEvent>().size shouldBe 1
        savedEvents.filterIsInstance<WarnEvent>() shouldBe emptyList()
    }

    "Factory rejected: WarnEvent emitted, no AnswerEvent created, agent stays suspended" {
        val caseId = UUID.randomUUID()
        val savedEvents = mutableListOf<CaseEvent>()
        val factoryCallCount = mutableListOf<String>()
        val rejection = Result.failure<Unit>(FactoryCheckpointException("REVISION_CONFLICT", "Stale revision"))

        val (runtime, question) = runtimeWithPendingQuestion(caseId, savedEvents, rejection, factoryCallCount)

        runtime.addUserMessage(
            actor = userActor,
            content = listOf(MessageContent.Text("Approve")),
            answerToEventId = question.id,
        )

        // Factory was called
        factoryCallCount shouldBe listOf("Approve")
        // No AnswerEvent
        savedEvents.filterIsInstance<AnswerEvent>() shouldBe emptyList()
        // WarnEvent emitted with reason
        val warns = savedEvents.filterIsInstance<WarnEvent>()
        warns.size shouldBe 1
        warns[0].message shouldBe "The Factory could not accept your decision: Stale revision. Please try again."
    }

    "Factory gate is skipped when question has no checkpoint (ordinary question)" {
        val caseId = UUID.randomUUID()
        val savedEvents = mutableListOf<CaseEvent>()
        val factoryCallCount = mutableListOf<String>()

        val (runtime) = buildRuntime(caseId, savedEvents, Result.success(Unit), factoryCallCount)
        // Push a question WITHOUT a Factory checkpoint
        val question = questionEvent(caseId, checkpoint = null)
        runtime.pushEvents(
            listOf(
                MessageEvent(
                    namespaceId = namespaceId,
                    caseId = caseId,
                    actor = userActor,
                    content = listOf(MessageContent.Text("start")),
                    timestamp = Instant.EPOCH.plusSeconds(1),
                ),
                AgentFinishedEvent(
                    namespaceId = namespaceId,
                    caseId = caseId,
                    agentId = agentId,
                    agentName = "test-agent",
                    timestamp = Instant.EPOCH.plusSeconds(2),
                ),
                question,
            ),
        )

        runtime.addUserMessage(
            actor = userActor,
            content = listOf(MessageContent.Text("my answer")),
            answerToEventId = question.id,
        )

        // Factory must NOT have been called
        factoryCallCount shouldBe emptyList()
        // AnswerEvent must be created normally
        savedEvents.filterIsInstance<AnswerEvent>().size shouldBe 1
        savedEvents.filterIsInstance<WarnEvent>() shouldBe emptyList()
    }

    "Factory gate is skipped when no client is wired (logs warning, proceeds)" {
        val caseId = UUID.randomUUID()
        val savedEvents = mutableListOf<CaseEvent>()

        // No factoryCheckpointClient — the default null
        val runtime = CaseRuntime(
            id = caseId,
            namespaceId = namespaceId,
            caseCreatedAt = Instant.EPOCH,
            updateStatusCallback = { _, _ -> },
            storeEvent = { event -> savedEvents.add(event); event },
            selectAgent = { _, _ -> emptyList() },
            isAgentAuthorized = { _, _ -> true },
            runAgent = { _, _, _, _, _ -> },
            factoryCheckpointClient = null,
        )
        val question = questionEvent(caseId) // has a checkpoint ref
        runtime.pushEvents(
            listOf(
                MessageEvent(
                    namespaceId = namespaceId,
                    caseId = caseId,
                    actor = userActor,
                    content = listOf(MessageContent.Text("start")),
                    timestamp = Instant.EPOCH.plusSeconds(1),
                ),
                AgentFinishedEvent(
                    namespaceId = namespaceId,
                    caseId = caseId,
                    agentId = agentId,
                    agentName = "test-agent",
                    timestamp = Instant.EPOCH.plusSeconds(2),
                ),
                question,
            ),
        )

        runtime.addUserMessage(
            actor = userActor,
            content = listOf(MessageContent.Text("Approve")),
            answerToEventId = question.id,
        )

        // No client: warning logged, AnswerEvent still created (fail-open for misconfiguration)
        savedEvents.filterIsInstance<AnswerEvent>().size shouldBe 1
        savedEvents.filterIsInstance<WarnEvent>() shouldBe emptyList()
    }

    "rejected answer can be retried: second attempt with Factory acceptance creates AnswerEvent" {
        val caseId = UUID.randomUUID()
        val savedEvents = mutableListOf<CaseEvent>()
        val callCount = mutableListOf<String>()
        // First call rejects, second accepts
        val results = mutableListOf(
            Result.failure(FactoryCheckpointException("REVISION_CONFLICT", "Stale")),
            Result.success(Unit),
        )
        val stubClient = object : FactoryCheckpointClient(
            "http://stub",
            okhttp3.OkHttpClient(),
            com.fasterxml.jackson.module.kotlin.jacksonObjectMapper(),
        ) {
            override suspend fun submitDecision(
                ref: FactoryCheckpointRef,
                decision: String,
                caseId: String,
                actorId: String,
            ): Result<Unit> {
                callCount += decision
                return results.removeFirst()
            }
        }
        val runtime = CaseRuntime(
            id = caseId,
            namespaceId = namespaceId,
            caseCreatedAt = Instant.EPOCH,
            updateStatusCallback = { _, _ -> },
            storeEvent = { event -> savedEvents.add(event); event },
            selectAgent = { _, _ -> emptyList() },
            isAgentAuthorized = { _, _ -> true },
            runAgent = { _, _, _, _, _ -> },
            factoryCheckpointClient = stubClient,
        )
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
                AgentFinishedEvent(
                    namespaceId = namespaceId,
                    caseId = caseId,
                    agentId = agentId,
                    agentName = "test-agent",
                    timestamp = Instant.EPOCH.plusSeconds(2),
                ),
                question,
            ),
        )

        // First attempt — rejected
        runtime.addUserMessage(userActor, listOf(MessageContent.Text("Approve")), question.id)
        savedEvents.filterIsInstance<AnswerEvent>() shouldBe emptyList()
        savedEvents.filterIsInstance<WarnEvent>().size shouldBe 1

        // Second attempt — accepted
        runtime.addUserMessage(userActor, listOf(MessageContent.Text("Approve")), question.id)
        savedEvents.filterIsInstance<AnswerEvent>().size shouldBe 1
        callCount shouldBe listOf("Approve", "Approve")
    }
})
