package io.whozoss.factory.agentattempt

import com.fasterxml.jackson.module.kotlin.jacksonObjectMapper
import com.fasterxml.jackson.module.kotlin.readValue
import io.mockk.every
import io.mockk.mockk
import io.mockk.slot
import io.mockk.verify
import io.whozoss.factory.agentattempt.domain.AgentAttemptStatus
import io.whozoss.factory.agentattempt.domain.DurableAgentAttempt
import io.whozoss.factory.agentattempt.domain.QuestionAlreadyAnsweredException
import io.whozoss.factory.agentattempt.domain.QuestionAnswerInvalidException
import io.whozoss.factory.agentattempt.domain.QuestionInteractionNotFoundException
import io.whozoss.factory.agentattempt.domain.QuestionSupersedeConflictException
import io.whozoss.factory.agentattempt.persistence.OutboxEventNode
import io.whozoss.factory.agentattempt.persistence.SpringDataNeo4jOutboxRepository
import io.whozoss.factory.agentattempt.service.AgentStepQuestionService
import io.whozoss.factory.agentattempt.service.AgentStepResultService
import io.whozoss.factory.agentattempt.service.DurableAgentAttemptService
import io.whozoss.factory.capability.CapabilityExecutionService
import io.whozoss.factory.error.RevisionConflictException
import io.whozoss.factory.persistence.TenantScope
import io.whozoss.factory.workflow.domain.HumanInteractionRecord
import io.whozoss.factory.workflow.persistence.HumanInteractionRepository
import io.whozoss.factory.workflow.sse.WorkflowSseHub
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Assertions.assertThrows
import org.junit.jupiter.api.Test
import java.time.Instant

/**
 * Unit tests of the answer path of [AgentStepQuestionService] (Phase 4
 * ask-step-question): the audited human answer closes the interaction exactly
 * once (revision-safe, single-use), supersedes attempt N and registers the
 * resumption attempt N+1 with a bounded resumption context.
 */
class AgentStepQuestionAnswerServiceTest {
    private val scope = TenantScope("org", "workstream")
    private val namespace = "00000000-0000-4000-8000-000000000001"
    private val workflowId = "workflow-1"
    private val stepId = "agent-step"
    private val attemptId = "$workflowId#$stepId"
    private val caseId = "case-1"
    private val interactionId = AgentStepQuestionService.deterministicInteractionId(attemptId, "sha256:question-1")
    private val now = Instant.parse("2026-10-03T00:00:00Z")

    private val resultService = mockk<AgentStepResultService>()
    private val attempts = mockk<DurableAgentAttemptService>()
    private val interactions = mockk<HumanInteractionRepository>()
    private val outbox = mockk<SpringDataNeo4jOutboxRepository>()
    private val sseHub = mockk<WorkflowSseHub>(relaxed = true)
    private val objectMapper = jacksonObjectMapper()
    private val service = AgentStepQuestionService(resultService, attempts, interactions, outbox, objectMapper, sseHub)

    init {
        every { outbox.save(any<OutboxEventNode>()) } answers { firstArg() }
    }

    private fun interaction(status: String = "waiting", revision: Int = 5, type: String = "FREE_TEXT", options: List<String> = emptyList()) =
        HumanInteractionRecord(
            interactionId = interactionId,
            namespaceId = namespace,
            workflowId = workflowId,
            stepId = stepId,
            interactionType = "agent_question",
            status = status,
            revision = revision,
            payload = linkedMapOf(
                "attemptId" to attemptId,
                "stepId" to stepId,
                "prompt" to "Proceed with deletion?",
                "questionType" to type,
                "options" to options,
                "contextHash" to "sha256:question-1",
            ),
        )

    private fun predecessor(status: AgentAttemptStatus = AgentAttemptStatus.WAITING_HUMAN, revision: Int = 5) = DurableAgentAttempt(
        attemptId, caseId, namespace, workflowId, stepId, 1, "Worker",
        ownerToken = "owner-1", status = status, revision = revision, brief = "the-turn-brief",
    )

    private fun stubHappyPath() {
        every { interactions.findOpenByWorkflowAndId(scope, workflowId, interactionId) } returns interaction()
        every { attempts.find(scope, namespace, workflowId, stepId, attemptId) } returns predecessor()
        every { attempts.nextAttemptNumber(scope, namespace, workflowId, stepId) } returns 2
        every { interactions.update(scope, namespace, workflowId, interactionId, 5, any()) } returns true
        every {
            attempts.supersede(scope, namespace, workflowId, stepId, attemptId, 5, now)
        } returns predecessor(AgentAttemptStatus.SUPERSEDED, revision = 6)
        every { attempts.registerRetry(scope, any(), now) } answers { secondArg() }
        every { interactions.appendEvent(scope, namespace, workflowId, any()) } returns Unit
    }

    @Test
    fun `an answer supersedes attempt N and registers attempt N+1 with the bounded resumption context`() {
        stubHappyPath()

        val answered = service.answer(scope, namespace, workflowId, interactionId, 5, "yes, proceed", "alice", now)

        val successorId = CapabilityExecutionService.retryAttemptId(workflowId, stepId, 2)
        assertThat(answered.status).isEqualTo("closed")
        assertThat(answered.supersededAttemptId).isEqualTo(attemptId)
        assertThat(answered.successorAttemptId).isEqualTo(successorId)
        assertThat(answered.successorAttemptNumber).isEqualTo(2)

        // Attempt N is superseded (terminal), fenced on its current revision.
        verify(exactly = 1) { attempts.supersede(scope, namespace, workflowId, stepId, attemptId, 5, now) }
        // Attempt N is NEVER reactivated: resumption is a brand-new attempt N+1.
        val registered = slot<DurableAgentAttempt>()
        verify(exactly = 1) { attempts.registerRetry(scope, capture(registered), now) }
        val successor = registered.captured
        assertThat(successor.attemptId).isEqualTo(successorId)
        assertThat(successor.attemptNumber).isEqualTo(2)
        assertThat(successor.status).isEqualTo(AgentAttemptStatus.PENDING)
        assertThat(successor.ownerToken).isNull()
        assertThat(successor.caseId).isEqualTo(caseId)
        assertThat(successor.brief).isEqualTo("the-turn-brief")

        // The resumption context carries the question, the audited answer/actor
        // and the predecessor links, within the documented bound.
        val context = objectMapper.readValue<Map<String, Any?>>(successor.resumptionContext!!)
        assertThat(context["question"]).isEqualTo("Proceed with deletion?")
        assertThat(context["answer"]).isEqualTo("yes, proceed")
        assertThat(context["actorId"]).isEqualTo("alice")
        assertThat(context["answeredAt"]).isEqualTo(now.toString())
        assertThat(context["predecessorAttemptId"]).isEqualTo(attemptId)
        assertThat(context["predecessorInteractionId"]).isEqualTo(interactionId)
        assertThat(successor.resumptionContext!!.toByteArray().size).isLessThanOrEqualTo(8192)

        // The interaction is CAS-closed with the audited answer and the N -> N+1 link.
        val closed = slot<HumanInteractionRecord>()
        verify(exactly = 1) { interactions.update(scope, namespace, workflowId, interactionId, 5, capture(closed)) }
        assertThat(closed.captured.status).isEqualTo("closed")
        assertThat(closed.captured.revision).isEqualTo(6)
        assertThat(closed.captured.payload["answer"]).isEqualTo("yes, proceed")
        assertThat(closed.captured.payload["actorId"]).isEqualTo("alice")
        assertThat(closed.captured.payload["answeredAt"]).isEqualTo(now.toString())
        assertThat(closed.captured.payload["successorAttemptId"]).isEqualTo(successorId)

        // The audited answer event records the human actor and the successor link.
        verify(exactly = 1) {
            interactions.appendEvent(
                scope, namespace, workflowId,
                match { it.eventType == "agent_question_answered" && it.actorId == "alice" && it.payload["successorAttemptId"] == successorId },
            )
        }
        verify(exactly = 0) {
            outbox.save(match<OutboxEventNode> { it.eventType == "agent_question_answered" })
        }
    }

    @Test
    fun `an absent legacy namespace uses the interaction authoritative namespace`() {
        stubHappyPath()

        val answered = service.answer(scope, null, workflowId, interactionId, 5, "yes", "alice", now)

        assertThat(answered.status).isEqualTo("closed")
        verify(exactly = 1) { attempts.find(scope, namespace, workflowId, stepId, attemptId) }
        verify(exactly = 1) { interactions.update(scope, namespace, workflowId, interactionId, 5, any()) }
    }

    @Test
    fun `a mismatching legacy namespace hint does not replace the interaction namespace`() {
        stubHappyPath()

        service.answer(scope, "00000000-0000-4000-8000-000000000099", workflowId, interactionId, 5, "yes", "alice", now)

        verify(exactly = 1) { attempts.find(scope, namespace, workflowId, stepId, attemptId) }
        verify(exactly = 1) { interactions.update(scope, namespace, workflowId, interactionId, 5, any()) }
    }

    @Test
    fun `answering twice is rejected and never creates an attempt N+2`() {
        every { interactions.findOpenByWorkflowAndId(scope, workflowId, interactionId) } returns interaction(status = "closed", revision = 6)

        val error = assertThrows(QuestionAlreadyAnsweredException::class.java) {
            service.answer(scope, namespace, workflowId, interactionId, 6, "again", "alice", now)
        }

        assertThat(error.errorCode).isEqualTo("QUESTION_ALREADY_ANSWERED")
        verify(exactly = 0) { attempts.supersede(any(), any(), any(), any(), any(), any(), any()) }
        verify(exactly = 0) { attempts.registerRetry(any(), any(), any()) }
        verify(exactly = 0) { attempts.nextAttemptNumber(any(), any(), any(), any()) }
    }

    @Test
    fun `a stale interaction revision is a conflict without any mutation`() {
        every { interactions.findOpenByWorkflowAndId(scope, workflowId, interactionId) } returns interaction(revision = 5)

        assertThrows(RevisionConflictException::class.java) {
            service.answer(scope, namespace, workflowId, interactionId, 4, "yes", "alice", now)
        }
        verify(exactly = 0) { attempts.supersede(any(), any(), any(), any(), any(), any(), any()) }
        verify(exactly = 0) { attempts.registerRetry(any(), any(), any()) }
    }

    @Test
    fun `a lost interaction CAS is a conflict and supersedes nothing`() {
        every { interactions.findOpenByWorkflowAndId(scope, workflowId, interactionId) } returns interaction()
        every { attempts.find(scope, namespace, workflowId, stepId, attemptId) } returns predecessor()
        every { attempts.nextAttemptNumber(scope, namespace, workflowId, stepId) } returns 2
        every { interactions.update(scope, namespace, workflowId, interactionId, 5, any()) } returns false

        assertThrows(RevisionConflictException::class.java) {
            service.answer(scope, namespace, workflowId, interactionId, 5, "yes", "alice", now)
        }
        verify(exactly = 0) { attempts.supersede(any(), any(), any(), any(), any(), any(), any()) }
        verify(exactly = 0) { attempts.registerRetry(any(), any(), any()) }
    }

    @Test
    fun `an answer for a non-waiting attempt is a supersede conflict`() {
        every { interactions.findOpenByWorkflowAndId(scope, workflowId, interactionId) } returns interaction()
        every { attempts.find(scope, namespace, workflowId, stepId, attemptId) } returns predecessor(AgentAttemptStatus.RUNNING)

        val error = assertThrows(QuestionSupersedeConflictException::class.java) {
            service.answer(scope, namespace, workflowId, interactionId, 5, "yes", "alice", now)
        }
        assertThat(error.errorCode).isEqualTo("QUESTION_SUPERSEDE_CONFLICT")
        verify(exactly = 0) { interactions.update(any(), any(), any(), any(), any(), any()) }
    }

    @Test
    fun `an unknown interaction or a non-question interaction is not found`() {
        every { interactions.findOpenByWorkflowAndId(scope, workflowId, interactionId) } returns null
        assertThat(assertThrows(QuestionInteractionNotFoundException::class.java) {
            service.answer(scope, namespace, workflowId, interactionId, 5, "yes", "alice", now)
        }.errorCode).isEqualTo("QUESTION_INTERACTION_NOT_FOUND")

        every { interactions.findOpenByWorkflowAndId(scope, workflowId, interactionId) } returns interaction().copy(interactionType = "approval")
        assertThat(assertThrows(QuestionInteractionNotFoundException::class.java) {
            service.answer(scope, namespace, workflowId, interactionId, 5, "yes", "alice", now)
        }.errorCode).isEqualTo("QUESTION_INTERACTION_NOT_FOUND")
    }

    @Test
    fun `the answer is validated against the question type`() {
        every { interactions.findOpenByWorkflowAndId(scope, workflowId, interactionId) } returns
            interaction(type = "SINGLE_CHOICE", options = listOf("A", "B"))

        assertThat(assertThrows(QuestionAnswerInvalidException::class.java) {
            service.answer(scope, namespace, workflowId, interactionId, 5, "C", "alice", now)
        }.errorCode).isEqualTo("QUESTION_ANSWER_INVALID")

        assertThat(assertThrows(QuestionAnswerInvalidException::class.java) {
            service.answer(scope, namespace, workflowId, interactionId, 5, "   ", "alice", now)
        }.errorCode).isEqualTo("QUESTION_ANSWER_INVALID")

        verify(exactly = 0) { attempts.registerRetry(any(), any(), any()) }
    }

    @Test
    fun `a blank actor identity is rejected`() {
        assertThat(assertThrows(QuestionAnswerInvalidException::class.java) {
            service.answer(scope, namespace, workflowId, interactionId, 5, "yes", "", now)
        }.errorCode).isEqualTo("QUESTION_ANSWER_INVALID")
        verify(exactly = 0) { interactions.findOpenByWorkflowAndId(any(), any(), any()) }
    }
}
