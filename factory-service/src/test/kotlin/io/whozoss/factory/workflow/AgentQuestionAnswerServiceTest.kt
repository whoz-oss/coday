package io.whozoss.factory.workflow

import io.mockk.every
import io.mockk.mockk
import io.mockk.verify
import io.whozoss.factory.adapter.agentos.AgentOsExecutionAdapter
import io.whozoss.factory.adapter.agentos.CaseEventView
import io.whozoss.factory.agentattempt.domain.AgentAttemptStatus
import io.whozoss.factory.agentattempt.domain.DurableAgentAttempt
import io.whozoss.factory.agentattempt.service.DurableAgentAttemptService
import io.whozoss.factory.error.FactoryException
import io.whozoss.factory.persistence.TenantScope
import io.whozoss.factory.workflow.persistence.HumanInteractionRepository
import io.whozoss.factory.workflow.persistence.WorkflowEvidenceRepository
import io.whozoss.factory.workflow.persistence.WorkflowRepository
import io.whozoss.factory.workflow.service.WorkflowService
import io.whozoss.factory.workflow.sse.WorkflowSseHub
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Assertions.assertThrows
import org.junit.jupiter.api.Test

class AgentQuestionAnswerServiceTest {
    private val scope = TenantScope("org", "workstream")
    private val namespace = "00000000-0000-4000-8000-000000000001"
    private val workflowId = "workflow-1"
    private val stepId = "agent-step"
    private val attemptId = "$workflowId#$stepId"
    private val caseId = "case-1"
    private val attempts = mockk<DurableAgentAttemptService>()
    private val adapter = mockk<AgentOsExecutionAdapter>()
    private val service = WorkflowService(
        mockk<WorkflowRepository>(),
        mockk<WorkflowEvidenceRepository>(),
        mockk<HumanInteractionRepository>(),
        mockk<WorkflowSseHub>(relaxed = true),
        durableAgentAttemptService = attempts,
        agentOsExecutionAdapter = adapter,
    )

    private fun attempt(status: AgentAttemptStatus = AgentAttemptStatus.WAITING_HUMAN) = DurableAgentAttempt(
        attemptId, caseId, namespace, workflowId, stepId, 1, "agent", status = status,
    )

    private fun question(type: String = "FREE_TEXT", options: List<String> = emptyList(), userId: String? = null) = CaseEventView(
        "question-1", CaseEventView.QUESTION_EVENT, caseId, null,
        buildMap {
            put("id", "question-1")
            put("type", CaseEventView.QUESTION_EVENT)
            put("caseId", caseId)
            put("question", "Choose")
            put("questionType", type)
            if (options.isNotEmpty()) put("options", options)
            if (userId != null) put("userId", userId)
        },
    )

    @Test
    fun `accepted answer forwards exact correlation and actual answering identity without state transition`() {
        every { attempts.find(scope, namespace, workflowId, stepId, attemptId) } returns attempt()
        every { adapter.persistedEvents(caseId) } returns listOf(question())
        every { adapter.answerQuestion(caseId, "question-1", "custom", attemptId, "alice") } returns Unit

        val result = service.submitAgentQuestionAnswer(scope, namespace, workflowId, stepId, "question-1", "custom", "alice")

        assertThat(result.status).isEqualTo(202)
        assertThat((result.data as Map<*, *>)["status"]).isEqualTo("accepted")
        verify(exactly = 1) { adapter.answerQuestion(caseId, "question-1", "custom", attemptId, "alice") }
        verify(exactly = 0) { attempts.transition(any(), any(), any(), any(), any(), any(), any(), any(), any()) }
    }

    @Test
    fun `internal recipient id is delegated to AgentOS identity resolution`() {
        every { attempts.find(scope, namespace, workflowId, stepId, attemptId) } returns attempt()
        every { adapter.persistedEvents(caseId) } returns listOf(question(userId = "agentos-internal-user-uuid"))
        every { adapter.answerQuestion(caseId, "question-1", "answer", attemptId, "alice") } returns Unit

        val result = service.submitAgentQuestionAnswer(
            scope, namespace, workflowId, stepId, "question-1", "answer", "alice",
        )

        assertThat(result.status).isEqualTo(202)
        verify(exactly = 1) { adapter.answerQuestion(caseId, "question-1", "answer", attemptId, "alice") }
    }

    @Test
    fun `blank authenticated identity is denied without forwarding`() {
        every { attempts.find(scope, namespace, workflowId, stepId, attemptId) } returns attempt()
        every { adapter.persistedEvents(caseId) } returns listOf(question(userId = "agentos-internal-user-uuid"))

        val error = assertThrows(FactoryException::class.java) {
            service.submitAgentQuestionAnswer(scope, namespace, workflowId, stepId, "question-1", "answer", "")
        }
        assertThat(error.errorCode).isEqualTo("AGENT_ANSWER_IDENTITY_REQUIRED")
        verify(exactly = 0) { adapter.answerQuestion(any(), any(), any(), any(), any()) }
    }

    @Test
    fun `duplicate persisted answer is rejected`() {
        every { attempts.find(scope, namespace, workflowId, stepId, attemptId) } returns attempt()
        every { adapter.persistedEvents(caseId) } returns listOf(
            question(),
            CaseEventView("answer-1", CaseEventView.ANSWER_EVENT, caseId, null, mapOf("questionId" to "question-1")),
        )

        val error = assertThrows(FactoryException::class.java) {
            service.submitAgentQuestionAnswer(scope, namespace, workflowId, stepId, "question-1", "answer", "alice")
        }
        assertThat(error.errorCode).isEqualTo("AGENT_QUESTION_ALREADY_ANSWERED")
        verify(exactly = 0) { adapter.answerQuestion(any(), any(), any(), any(), any()) }
    }

    @Test
    fun `single choice validates options while open choice permits custom text and oauth fails closed`() {
        every { attempts.find(scope, namespace, workflowId, stepId, attemptId) } returns attempt()
        every { adapter.persistedEvents(caseId) } returns listOf(question("SINGLE_CHOICE", listOf("A", "B")))
        assertThat(assertThrows(FactoryException::class.java) {
            service.submitAgentQuestionAnswer(scope, namespace, workflowId, stepId, "question-1", "custom", "alice")
        }.errorCode).isEqualTo("INVALID_AGENT_ANSWER")

        every { adapter.persistedEvents(caseId) } returns listOf(question("OPEN_CHOICE", listOf("A", "B")))
        every { adapter.answerQuestion(caseId, "question-1", "custom", attemptId, "alice") } returns Unit
        assertThat(service.submitAgentQuestionAnswer(scope, namespace, workflowId, stepId, "question-1", "custom", "alice").status).isEqualTo(202)

        every { adapter.persistedEvents(caseId) } returns listOf(question("OAUTH_AUTHORIZE"))
        assertThat(assertThrows(FactoryException::class.java) {
            service.submitAgentQuestionAnswer(scope, namespace, workflowId, stepId, "question-1", "authorize", "alice")
        }.errorCode).isEqualTo("UNSUPPORTED_AGENT_QUESTION_TYPE")
    }

    @Test
    fun `attempt correlation and waiting status are authoritative`() {
        every { attempts.find(scope, namespace, workflowId, stepId, attemptId) } returns attempt(AgentAttemptStatus.RUNNING)
        assertThat(assertThrows(FactoryException::class.java) {
            service.submitAgentQuestionAnswer(scope, namespace, workflowId, stepId, "question-1", "answer", "alice")
        }.errorCode).isEqualTo("AGENT_QUESTION_STALE")
        verify(exactly = 0) { adapter.persistedEvents(any()) }
    }
}
