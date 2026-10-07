package io.whozoss.factory.agentattempt

import com.fasterxml.jackson.module.kotlin.jacksonObjectMapper
import io.mockk.every
import io.mockk.mockk
import io.mockk.slot
import io.mockk.verify
import io.whozoss.factory.agentattempt.domain.AgentAttemptStatus
import io.whozoss.factory.agentattempt.domain.AgentStepResultCapability
import io.whozoss.factory.agentattempt.domain.DurableAgentAttempt
import io.whozoss.factory.agentattempt.domain.QuestionAlreadyAskedException
import io.whozoss.factory.agentattempt.domain.QuestionAttemptNotWaitableException
import io.whozoss.factory.agentattempt.domain.QuestionSchemaInvalidException
import io.whozoss.factory.agentattempt.domain.ResultCapabilityExpiredException
import io.whozoss.factory.agentattempt.domain.ResultCapabilityInvalidException
import io.whozoss.factory.agentattempt.domain.ResultIdentityMismatchException
import io.whozoss.factory.agentattempt.persistence.OutboxEventNode
import io.whozoss.factory.agentattempt.persistence.SpringDataNeo4jOutboxRepository
import io.whozoss.factory.agentattempt.service.AgentStepQuestionService
import io.whozoss.factory.agentattempt.service.AgentStepResultService
import io.whozoss.factory.agentattempt.service.DurableAgentAttemptService
import io.whozoss.factory.persistence.TenantScope
import io.whozoss.factory.workflow.domain.HumanInteractionRecord
import io.whozoss.factory.workflow.persistence.HumanInteractionRepository
import io.whozoss.factory.workflow.sse.WorkflowSseHub
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Assertions.assertThrows
import org.junit.jupiter.api.Test
import java.time.Instant

/**
 * Unit tests of the ask path of [AgentStepQuestionService] (Phase 4
 * ask-step-question): the worker question transitions the attempt to
 * `waiting_human` and persists the durable `agent_question` interaction in
 * the same logical operation, without ever redeeming the result capability.
 */
class AgentStepQuestionServiceTest {
    private val scope = TenantScope("org", "workstream")
    private val namespace = "00000000-0000-4000-8000-000000000001"
    private val workflowId = "workflow-1"
    private val stepId = "agent-step"
    private val attemptId = "$workflowId#$stepId"
    private val caseId = "case-1"
    private val agentName = "Worker"
    private val token = "capability-token"
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

    private fun capability(expiresAt: Instant = now.plusSeconds(900)) = AgentStepResultCapability(
        type = "capability-issued",
        capabilityId = "cap-1",
        tokenHash = "sha256:hash",
        attemptId = attemptId,
        workflowId = workflowId,
        stepId = stepId,
        namespaceId = namespace,
        caseId = caseId,
        agentName = agentName,
        briefHash = "sha256:brief",
        issuedAt = now.minusSeconds(60).toString(),
        expiresAt = expiresAt.toString(),
        submissionBudget = 1,
    )

    private fun attempt(status: AgentAttemptStatus, ownerToken: String? = "owner-1", revision: Int = 4) = DurableAgentAttempt(
        attemptId, caseId, namespace, workflowId, stepId, 1, agentName,
        ownerToken = ownerToken, status = status, revision = revision,
    )

    private fun question(prompt: String = "Proceed with deletion?") = objectMapper.readTree(
        """{"prompt":"$prompt","type":"FREE_TEXT","contextHash":"sha256:question-1"}""",
    )

    private fun stubHappyPath() {
        every { resultService.resolveCapability(scope, token) } returns capability()
        every { attempts.find(scope, namespace, workflowId, stepId, attemptId) } returns attempt(AgentAttemptStatus.RUNNING)
        every { interactions.find(scope, namespace, workflowId, any()) } returns null
        every {
            attempts.transition(scope, namespace, workflowId, stepId, attemptId, "owner-1", AgentAttemptStatus.WAITING_HUMAN, null, now)
        } returns attempt(AgentAttemptStatus.WAITING_HUMAN, revision = 5)
        every { interactions.insert(scope, any()) } answers { secondArg() }
        every { interactions.appendEvent(scope, namespace, workflowId, any()) } returns Unit
    }

    @Test
    fun `asking transitions the attempt to waiting_human and persists the linked interaction in the same operation`() {
        stubHappyPath()

        val asked = service.ask(scope, token, attemptId, question(), caseId, agentName, null, now)

        assertThat(asked.attemptId).isEqualTo(attemptId)
        assertThat(asked.status).isEqualTo("waiting_human")
        assertThat(asked.idempotent).isFalse()
        assertThat(asked.interactionId).isEqualTo(
            AgentStepQuestionService.deterministicInteractionId(attemptId, "sha256:question-1"),
        )

        // The attempt parks in waiting_human, fenced on the durable lease owner.
        verify(exactly = 1) {
            attempts.transition(scope, namespace, workflowId, stepId, attemptId, "owner-1", AgentAttemptStatus.WAITING_HUMAN, null, now)
        }
        // The durable interaction carries every link: attempt, step, workflow, namespace, question.
        val persisted = slot<HumanInteractionRecord>()
        verify(exactly = 1) { interactions.insert(scope, capture(persisted)) }
        assertThat(persisted.captured.interactionType).isEqualTo("agent_question")
        assertThat(persisted.captured.status).isEqualTo("waiting")
        assertThat(persisted.captured.namespaceId).isEqualTo(namespace)
        assertThat(persisted.captured.workflowId).isEqualTo(workflowId)
        assertThat(persisted.captured.stepId).isEqualTo(stepId)
        assertThat(persisted.captured.interactionId).isEqualTo(asked.interactionId)
        assertThat(persisted.captured.payload["attemptId"]).isEqualTo(attemptId)
        assertThat(persisted.captured.payload["prompt"]).isEqualTo("Proceed with deletion?")
        assertThat(persisted.captured.payload["questionType"]).isEqualTo("FREE_TEXT")
        assertThat(persisted.captured.payload["contextHash"]).isEqualTo("sha256:question-1")
        // The audit event and the deferred notification are recorded.
        verify(exactly = 1) {
            interactions.appendEvent(
                scope, namespace, workflowId,
                match { it.eventType == "agent_question_asked" && it.interactionId == asked.interactionId },
            )
        }
        verify(exactly = 1) {
            outbox.save(match<OutboxEventNode> { it.eventType == "agent_question_asked" && it.status == "pending" })
        }
        // The result capability is resolved READ-ONLY, never redeemed.
        verify(exactly = 1) { resultService.resolveCapability(scope, token) }
    }

    @Test
    fun `re-asking the same question is an idempotent replay collapsing onto the same interaction`() {
        val interactionId = AgentStepQuestionService.deterministicInteractionId(attemptId, "sha256:question-1")
        every { resultService.resolveCapability(scope, token) } returns capability()
        every { attempts.find(scope, namespace, workflowId, stepId, attemptId) } returns attempt(AgentAttemptStatus.WAITING_HUMAN)
        every { interactions.find(scope, namespace, workflowId, interactionId) } returns HumanInteractionRecord(
            interactionId = interactionId,
            namespaceId = namespace,
            workflowId = workflowId,
            stepId = stepId,
            interactionType = "agent_question",
            status = "waiting",
            revision = 5,
            payload = mapOf("attemptId" to attemptId, "contextHash" to "sha256:question-1"),
        )

        val replay = service.ask(scope, token, attemptId, question(), caseId, agentName, null, now)

        assertThat(replay.idempotent).isTrue()
        assertThat(replay.interactionId).isEqualTo(interactionId)
        verify(exactly = 0) { attempts.transition(any(), any(), any(), any(), any(), any(), any(), any(), any()) }
        verify(exactly = 0) { interactions.insert(any(), any()) }
    }

    @Test
    fun `a waiting attempt with a different recorded question is rejected`() {
        val interactionId = AgentStepQuestionService.deterministicInteractionId(attemptId, "sha256:question-1")
        every { resultService.resolveCapability(scope, token) } returns capability()
        every { attempts.find(scope, namespace, workflowId, stepId, attemptId) } returns attempt(AgentAttemptStatus.WAITING_HUMAN)
        every { interactions.find(scope, namespace, workflowId, interactionId) } returns HumanInteractionRecord(
            interactionId, namespace, workflowId, stepId, "agent_question", "waiting", 5,
            mapOf("attemptId" to attemptId, "contextHash" to "sha256:other-question"),
        )

        val error = assertThrows(QuestionAlreadyAskedException::class.java) {
            service.ask(scope, token, attemptId, question(), caseId, agentName, null, now)
        }
        assertThat(error.errorCode).isEqualTo("QUESTION_ALREADY_ASKED")
    }

    @Test
    fun `a non-running attempt cannot ask a question`() {
        every { resultService.resolveCapability(scope, token) } returns capability()
        every { attempts.find(scope, namespace, workflowId, stepId, attemptId) } returns attempt(AgentAttemptStatus.SUCCEEDED)
        every { interactions.find(scope, namespace, workflowId, any()) } returns null

        val error = assertThrows(QuestionAttemptNotWaitableException::class.java) {
            service.ask(scope, token, attemptId, question(), caseId, agentName, null, now)
        }
        assertThat(error.errorCode).isEqualTo("QUESTION_ATTEMPT_NOT_WAITABLE")
        verify(exactly = 0) { attempts.transition(any(), any(), any(), any(), any(), any(), any(), any(), any()) }
    }

    @Test
    fun `an invalid question schema is rejected before any mutation`() {
        every { resultService.resolveCapability(scope, token) } returns capability()

        val error = assertThrows(QuestionSchemaInvalidException::class.java) {
            service.ask(scope, token, attemptId, objectMapper.readTree("""{"prompt":""}"""), caseId, agentName, null, now)
        }
        assertThat(error.errorCode).isEqualTo("QUESTION_SCHEMA_INVALID")
        verify(exactly = 0) { attempts.find(any(), any(), any(), any(), any()) }
    }

    @Test
    fun `an unknown or expired capability is rejected`() {
        every { resultService.resolveCapability(scope, token) } returns null
        assertThat(assertThrows(ResultCapabilityInvalidException::class.java) {
            service.ask(scope, token, attemptId, question(), caseId, agentName, null, now)
        }.errorCode).isEqualTo("RESULT_CAPABILITY_INVALID")

        every { resultService.resolveCapability(scope, token) } returns capability(expiresAt = now.minusSeconds(1))
        assertThat(assertThrows(ResultCapabilityExpiredException::class.java) {
            service.ask(scope, token, attemptId, question(), caseId, agentName, null, now)
        }.errorCode).isEqualTo("RESULT_CAPABILITY_EXPIRED")
    }

    @Test
    fun `an observed identity diverging from the capability is rejected as an identity mismatch`() {
        every { resultService.resolveCapability(scope, token) } returns capability()

        assertThat(assertThrows(ResultIdentityMismatchException::class.java) {
            service.ask(scope, token, "other-attempt", question(), caseId, agentName, null, now)
        }.errorCode).isEqualTo("RESULT_IDENTITY_MISMATCH")
        assertThat(assertThrows(ResultIdentityMismatchException::class.java) {
            service.ask(scope, token, attemptId, question(), "other-case", agentName, null, now)
        }.errorCode).isEqualTo("RESULT_IDENTITY_MISMATCH")
        assertThat(assertThrows(ResultIdentityMismatchException::class.java) {
            service.ask(scope, token, attemptId, question(), caseId, "OtherAgent", null, now)
        }.errorCode).isEqualTo("RESULT_IDENTITY_MISMATCH")
        assertThat(assertThrows(ResultIdentityMismatchException::class.java) {
            service.ask(scope, token, attemptId, question(), caseId, agentName, "other-namespace", now)
        }.errorCode).isEqualTo("RESULT_IDENTITY_MISMATCH")
        verify(exactly = 0) { attempts.transition(any(), any(), any(), any(), any(), any(), any(), any(), any()) }
    }

    @Test
    fun `a question whose expiry already passed is rejected`() {
        every { resultService.resolveCapability(scope, token) } returns capability()

        val expired = objectMapper.readTree(
            """{"prompt":"P","type":"FREE_TEXT","contextHash":"h","expiresAt":"2020-01-01T00:00:00Z"}""",
        )
        assertThat(assertThrows(QuestionSchemaInvalidException::class.java) {
            service.ask(scope, token, attemptId, expired, caseId, agentName, null, now)
        }.errorCode).isEqualTo("QUESTION_SCHEMA_INVALID")
    }
}
