package io.whozoss.agentos.agent

import io.kotest.core.spec.style.StringSpec
import io.kotest.matchers.shouldBe
import io.kotest.matchers.types.shouldBeInstanceOf
import io.mockk.every
import io.mockk.mockk
import io.whozoss.agentos.sdk.agent.Agent
import io.whozoss.agentos.sdk.caseEvent.AgentFinishedEvent
import io.whozoss.agentos.sdk.caseEvent.CaseEvent
import io.whozoss.agentos.sdk.caseEvent.QuestionEvent
import io.whozoss.agentos.sdk.caseEvent.QuestionType
import io.whozoss.agentos.sdk.entity.EntityMetadata
import kotlinx.coroutines.flow.FlowCollector
import mu.KLogger
import java.util.UUID

/**
 * Unit tests for [emitInterruptAndFinishEvents].
 *
 * Verifies that the [QuestionEvent] emitted on [AgentInterrupt.AwaitAnswer] carries
 * exactly the fields supplied by the interrupt — in particular [QuestionEvent.userId],
 * which must flow from [AgentInterrupt.AwaitAnswer.userId] without any transformation.
 */
class AgentInterruptHandlerUnitSpec : StringSpec({

    val namespaceId: UUID = UUID.randomUUID()
    val caseId: UUID = UUID.randomUUID()
    val agentId: UUID = UUID.randomUUID()
    val logger = mockk<KLogger>(relaxed = true)

    fun buildAgent(id: UUID = agentId): Agent =
        mockk<Agent> {
            every { metadata } returns EntityMetadata(id = id)
            // Agent.id is defined as `get() = metadata.id` on the Entity interface,
            // but MockK does not automatically delegate getId() to the metadata stub.
            // Stubbing it explicitly is required whenever the production code calls agent.id.
            every { this@mockk.id } returns id
            every { name } returns "test-agent"
            every { llmProvider } returns "anthropic"
            every { llmModel } returns "claude-3"
        }

    // -----------------------------------------------------------------------
    // AwaitAnswer: QuestionEvent fields
    // -----------------------------------------------------------------------

    "emitInterruptAndFinishEvents emits AgentFinishedEvent then QuestionEvent for AwaitAnswer" {
        val emitted = mutableListOf<CaseEvent>()
        val collector = FlowCollector<CaseEvent> { emitted.add(it) }

        val interrupt = AgentInterrupt.AwaitAnswer(
            question = "What is your name?",
            questionType = QuestionType.FREE_TEXT,
        )

        collector.emitInterruptAndFinishEvents(buildAgent(), interrupt, namespaceId, caseId, logger)

        emitted.size shouldBe 2
        emitted[0].shouldBeInstanceOf<AgentFinishedEvent>()
        emitted[1].shouldBeInstanceOf<QuestionEvent>()
    }

    "QuestionEvent carries userId from AwaitAnswer when userId is non-null" {
        // This is the primary use-case: the tool has a resolved user from ToolContext
        // and stamps AwaitAnswer.userId with it. The handler must forward it verbatim
        // to the QuestionEvent so downstream consumers (push notifications, UI) know
        // which user the question is directed at.
        val userId = UUID.randomUUID()
        val emitted = mutableListOf<CaseEvent>()
        val collector = FlowCollector<CaseEvent> { emitted.add(it) }

        val interrupt = AgentInterrupt.AwaitAnswer(
            question = "Pick one?",
            questionType = QuestionType.FREE_TEXT,
            userId = userId,
        )

        collector.emitInterruptAndFinishEvents(buildAgent(), interrupt, namespaceId, caseId, logger)

        val questionEvent = emitted.filterIsInstance<QuestionEvent>().single()
        questionEvent.userId shouldBe userId
    }

    "QuestionEvent has null userId when AwaitAnswer.userId is null" {
        // When the execution context has no resolved user (webhook, system call, etc.),
        // AwaitAnswer.userId is null and the QuestionEvent must stay unaddressed.
        // No fallback is applied — null in, null out.
        val emitted = mutableListOf<CaseEvent>()
        val collector = FlowCollector<CaseEvent> { emitted.add(it) }

        val interrupt = AgentInterrupt.AwaitAnswer(
            question = "Any user can answer?",
            questionType = QuestionType.FREE_TEXT,
            userId = null,
        )

        collector.emitInterruptAndFinishEvents(buildAgent(), interrupt, namespaceId, caseId, logger)

        val questionEvent = emitted.filterIsInstance<QuestionEvent>().single()
        questionEvent.userId shouldBe null
    }

    "QuestionEvent carries question text, options and questionType from AwaitAnswer" {
        val emitted = mutableListOf<CaseEvent>()
        val collector = FlowCollector<CaseEvent> { emitted.add(it) }

        val interrupt = AgentInterrupt.AwaitAnswer(
            question = "Choose a colour",
            options = listOf("Red", "Blue"),
            questionType = QuestionType.SINGLE_CHOICE,
            userId = null,
        )

        collector.emitInterruptAndFinishEvents(buildAgent(), interrupt, namespaceId, caseId, logger)

        val questionEvent = emitted.filterIsInstance<QuestionEvent>().single()
        questionEvent.question shouldBe "Choose a colour"
        questionEvent.options shouldBe listOf("Red", "Blue")
        questionEvent.questionType shouldBe QuestionType.SINGLE_CHOICE
    }

    // -----------------------------------------------------------------------
    // AwaitAnswer: AgentFinishedEvent fields
    // -----------------------------------------------------------------------

    "AgentFinishedEvent carries agent identity from the Agent parameter" {
        val emitted = mutableListOf<CaseEvent>()
        val collector = FlowCollector<CaseEvent> { emitted.add(it) }
        val agent = buildAgent(agentId)

        collector.emitInterruptAndFinishEvents(
            agent,
            AgentInterrupt.AwaitAnswer(question = "?"),
            namespaceId,
            caseId,
            logger,
        )

        val finished = emitted.filterIsInstance<AgentFinishedEvent>().single()
        finished.agentId shouldBe agentId
        finished.agentName shouldBe "test-agent"
    }
})
