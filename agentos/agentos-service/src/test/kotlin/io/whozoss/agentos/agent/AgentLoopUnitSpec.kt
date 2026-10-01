package io.whozoss.agentos.agent

import com.fasterxml.jackson.databind.ObjectMapper
import io.kotest.core.spec.style.StringSpec
import io.kotest.matchers.collections.shouldHaveSize
import io.kotest.matchers.shouldBe
import io.kotest.matchers.types.shouldBeInstanceOf
import io.whozoss.agentos.sdk.actor.Actor
import io.whozoss.agentos.sdk.actor.ActorRole
import io.whozoss.agentos.sdk.caseEvent.AgentFinishedEvent
import io.whozoss.agentos.sdk.caseEvent.CaseEvent
import io.whozoss.agentos.sdk.caseEvent.MessageContent
import io.whozoss.agentos.sdk.caseEvent.MessageEvent
import io.whozoss.agentos.sdk.caseEvent.ThinkingEvent
import io.whozoss.agentos.sdk.entity.EntityMetadata
import kotlinx.coroutines.flow.toList
import java.util.UUID

/**
 * Unit tests for [AgentLoop].
 *
 * AgentLoop is a zero-LLM agent: no Spring AI, no chat client, no mocks needed.
 * All tests collect the emitted [CaseEvent] flow and assert on its structure.
 */
class AgentLoopUnitSpec : StringSpec({

    val objectMapper = ObjectMapper()
    val namespaceId: UUID = UUID.randomUUID()
    val caseId: UUID = UUID.randomUUID()

    fun agent(name: String = "loop-agent") = AgentLoop(
        metadata = EntityMetadata(id = UUID.randomUUID()),
        name = name,
        objectMapper = objectMapper,
    )

    fun userMessage(text: String) = MessageEvent(
        namespaceId = namespaceId,
        caseId = caseId,
        actor = Actor(id = "user-1", displayName = "Alice", role = ActorRole.USER),
        content = listOf(MessageContent.Text(text)),
    )

    fun agentMessage(text: String) = MessageEvent(
        namespaceId = namespaceId,
        caseId = caseId,
        actor = Actor(id = "agent-1", displayName = "loop-agent", role = ActorRole.AGENT),
        content = listOf(MessageContent.Text(text)),
    )

    val validPayload = """
        {
            "entityType": "TALENT",
            "filters": {"status": "active"},
            "searchOptions": {"limit": 10},
            "act": {"agentName": "talent-analyzer", "promptTemplate": "Analyse {entityId}"}
        }
    """.trimIndent()

    // -------------------------------------------------------------------------
    // Happy path
    // -------------------------------------------------------------------------

    "run with valid payload emits ThinkingEvent then AgentFinishedEvent" {
        val events: List<CaseEvent> = listOf(userMessage(validPayload))

        val emitted = agent().run(events).toList()

        emitted shouldHaveSize 2
        emitted[0].shouldBeInstanceOf<ThinkingEvent>()
        emitted[1].shouldBeInstanceOf<AgentFinishedEvent>()
    }

    "run emits AgentFinishedEvent with correct agentName and no llmProvider" {
        val events: List<CaseEvent> = listOf(userMessage(validPayload))
        val loop = agent(name = "my-loop")

        val finished = loop.run(events).toList().last() as AgentFinishedEvent

        finished.agentName shouldBe "my-loop"
        finished.agentId shouldBe loop.id
        finished.llmProvider shouldBe null
        finished.llmModel shouldBe null
    }

    "run emits events with correct namespaceId and caseId" {
        val events: List<CaseEvent> = listOf(userMessage(validPayload))

        val emitted = agent().run(events).toList()

        emitted.forEach { event ->
            event.namespaceId shouldBe namespaceId
            event.caseId shouldBe caseId
        }
    }

    // -------------------------------------------------------------------------
    // shouldContinue = false — early exit before ThinkingEvent
    // -------------------------------------------------------------------------

    "run with shouldContinue=false emits only AgentFinishedEvent without ThinkingEvent" {
        val events: List<CaseEvent> = listOf(userMessage(validPayload))

        val emitted = agent().run(events, shouldContinue = { false }).toList()

        emitted shouldHaveSize 1
        emitted[0].shouldBeInstanceOf<AgentFinishedEvent>()
    }

    // -------------------------------------------------------------------------
    // Malformed / missing payload — fail-safe finish
    // -------------------------------------------------------------------------

    "run with invalid JSON payload emits ThinkingEvent then AgentFinishedEvent without throwing" {
        val events: List<CaseEvent> = listOf(userMessage("not json at all"))

        val emitted = agent().run(events).toList()

        emitted shouldHaveSize 2
        emitted[0].shouldBeInstanceOf<ThinkingEvent>()
        emitted[1].shouldBeInstanceOf<AgentFinishedEvent>()
    }

    "run with payload missing entityType emits ThinkingEvent then AgentFinishedEvent" {
        val noEntityType = """{"filters": {}}"""
        val events: List<CaseEvent> = listOf(userMessage(noEntityType))

        val emitted = agent().run(events).toList()

        emitted shouldHaveSize 2
        emitted[0].shouldBeInstanceOf<ThinkingEvent>()
        emitted[1].shouldBeInstanceOf<AgentFinishedEvent>()
    }

    "run with blank payload emits ThinkingEvent then AgentFinishedEvent" {
        val events: List<CaseEvent> = listOf(userMessage("   "))

        val emitted = agent().run(events).toList()

        emitted shouldHaveSize 2
        emitted[0].shouldBeInstanceOf<ThinkingEvent>()
        emitted[1].shouldBeInstanceOf<AgentFinishedEvent>()
    }

    "run with no user MessageEvent emits ThinkingEvent then AgentFinishedEvent" {
        // Only an agent message — no user turn.
        val events: List<CaseEvent> = listOf(agentMessage("I am the agent."))

        val emitted = agent().run(events).toList()

        emitted shouldHaveSize 2
        emitted[0].shouldBeInstanceOf<ThinkingEvent>()
        emitted[1].shouldBeInstanceOf<AgentFinishedEvent>()
    }

    "run with empty event list throws IllegalArgumentException" {
        io.kotest.assertions.throwables.shouldThrow<IllegalArgumentException> {
            agent().run(emptyList()).toList()
        }
    }

    // -------------------------------------------------------------------------
    // Agent identity
    // -------------------------------------------------------------------------

    "llmProvider and llmModel are sentinel values signalling no LLM usage" {
        val loop = agent()

        loop.llmProvider shouldBe AgentLoop.PROVIDER_NAME
        loop.llmModel shouldBe AgentLoop.MODEL_NAME
    }
})
