package io.whozoss.factory.workflow

import io.mockk.*
import io.whozoss.factory.agentattempt.domain.*
import io.whozoss.factory.agentattempt.service.AgentStepResultService
import io.whozoss.factory.agentattempt.service.DurableAgentAttemptService
import io.whozoss.factory.persistence.TenantScope
import io.whozoss.factory.workflow.domain.*
import io.whozoss.factory.workflow.persistence.*
import io.whozoss.factory.workflow.service.*
import org.assertj.core.api.Assertions.assertThat
import org.assertj.core.api.Assertions.assertThatThrownBy
import org.junit.jupiter.api.Test

class OutcomeTransitionServiceTest {
    private val repository = mockk<WorkflowRepository>(relaxed = true)
    private val evidence = mockk<WorkflowEvidenceRepository>(relaxed = true)
    private val interactions = mockk<HumanInteractionRepository>(relaxed = true)
    private val attempts = mockk<DurableAgentAttemptService>(relaxed = true)
    private val results = mockk<AgentStepResultService>(relaxed = true)
    private val submissions = mockk<SessionRunSubmissionService>(relaxed = true)
    private val service = OutcomeTransitionService(repository, evidence, interactions, attempts, results, submissions)
    private val scope = TenantScope("org", "stream")
    private val step = WorkflowStepDefinition("review", "Review", WorkflowStepResponsibility(ResponsibilityKind.AGENT, "Reviewer"), emptyList(),
        outcomeTransitions = OutcomeTransitions(fail = OutcomeAction("return", "review")))
    private val execution = mapOf("executionId" to "exec", "stepId" to "review", "executionNumber" to 1,
        "cycleNumber" to 0, "status" to "failed", "attemptId" to "attempt")
    private var instance = WorkflowInstanceRecord("ns", "wf", 1, "active", null,
        mapOf("status" to "running", "definitionVersion" to "1.0.0", "definitionHash" to "hash"),
        mapOf("schemaVersion" to "2", "steps" to listOf(mapOf("id" to "review", "status" to "failed")), "stepExecutions" to listOf(execution)))

    private fun fixture(code: String = "FACTORY_STEP_RESULT_FAILED", status: AgentStepResultStatus = AgentStepResultStatus.FAIL) {
        every { repository.findInstance(scope, "ns", "wf") } answers { instance }
        every { repository.findStepStates(scope, "ns", "wf") } returns listOf(WorkflowStepStateRecord("ns", "wf", "review", 1, "failed", mapOf("execution" to execution)))
        val envelope = AttemptContextEnvelope(organizationId = "org", workstreamId = "stream", namespaceId = "ns",
            workflowId = "wf", stepId = "review", attemptNumber = 1, agentName = "Reviewer", briefHash = "brief",
            inputs = mapOf("stepExecutionId" to "exec")).toJson()
        val attempt = DurableAgentAttempt("attempt", "case", "ns", "wf", "review", 1, "Reviewer", status = AgentAttemptStatus.FAILED,
            failureCode = code, contextEnvelope = envelope)
        every { attempts.findLatestForStep(scope, "ns", "wf", "review") } returns attempt
        every { attempts.findByWorkflow(scope, "ns", "wf") } returns listOf(attempt)
        every { results.acceptedResult(scope, "ns", "wf", "review", "attempt") } returns AgentStepResultSubmitted(
            "result-submitted", "result", "attempt", "wf", "review", "ns", "case", "Reviewer", "brief", status,
            "Rejected", emptyList(), AgentStepResultClaim(emptyList()), emptyList(), "2026-01-01T00:00:00Z", "hash")
        every { repository.updateInstance(scope, "ns", "wf", any(), any()) } answers { instance = arg(4); true }
        every { repository.publishProjection(any(), any(), any()) } answers { ProjectionPublishResult.Changed(arg(1)) }
        every { evidence.append(any(), any(), any(), any()) } answers { EvidenceAppendResult.Created(arg(3)) }
    }

    @Test
    fun `accepted finalized FAIL creates exactly one cycle and durable wakeup`() {
        fixture()
        assertThat(service.advance(scope, "ns", "wf", listOf(step), "/repo", null)).isTrue()
        val correction = instance.projection["correction"] as Map<*, *>
        assertThat(correction["cycleNumber"]).isEqualTo(1)
        val history = instance.projection["stepExecutions"] as List<*>
        assertThat(history).hasSize(2)
        assertThat((history.last() as Map<*, *>)["executionId"]).isNotEqualTo("exec")
        // AC1/AC2: the run stays active (ready), the rejected execution keeps its FAIL and attempt, the replay is pending.
        assertThat(instance.instance["status"]).isEqualTo("ready")
        assertThat(instance.projection["status"]).isEqualTo("ready")
        val triggering = history.first() as Map<*, *>
        assertThat(triggering["executionId"]).isEqualTo("exec")
        assertThat(triggering["status"]).isEqualTo("failed")
        assertThat(triggering["attemptId"]).isEqualTo("attempt")
        assertThat((history.last() as Map<*, *>)["status"]).isEqualTo("pending")
        assertThat(service.advance(scope, "ns", "wf", listOf(step), "/repo", null)).isFalse()
        verify(exactly = 1) { submissions.submit(scope, "ns", "wf", "/repo", any(), null) }
    }

    @Test
    fun `technical failures and NEEDS_RESEARCH cannot consume correction budget`() {
        for ((code, status) in listOf("AGENT_INDETERMINATE" to AgentStepResultStatus.FAIL,
            "RUNTIME_CLOSED" to AgentStepResultStatus.FAIL, "FACTORY_STEP_RESULT_FAILED" to AgentStepResultStatus.NEEDS_RESEARCH)) {
            fixture(code, status)
            assertThat(service.advance(scope, "ns", "wf", listOf(step), "/repo", null)).isFalse()
        }
        verify(exactly = 0) { submissions.submit(any(), any(), any(), any(), any(), any()) }
    }

    @Test
    fun `two completed correction cycles escalate without creating a third`() {
        fixture()
        instance = instance.copy(projection = instance.projection + mapOf(
            "correction" to mapOf("cycleNumber" to 2, "status" to "active"),
            "correctionCounts" to mapOf(CanonicalHash.sha256Hex("review") to 2)))
        assertThat(service.advance(scope, "ns", "wf", listOf(step), "/repo", null)).isTrue()
        assertThat((instance.projection["correction"] as Map<*, *>)["status"]).isEqualTo("waiting_human")
        assertThat(instance.instance["status"]).isEqualTo("waiting_human")
        verify(exactly = 1) { interactions.insert(scope, match { it.interactionType == "correction_exhausted" }) }
        verify(exactly = 0) { submissions.submit(any(), any(), any(), any(), any(), any()) }
    }

    @Test
    fun `accepted failure from another execution never starts correction`() {
        fixture()
        val latest = attempts.findLatestForStep(scope, "ns", "wf", "review")!!
        val envelope = AttemptContextEnvelope.fromJson(latest.contextEnvelope!!)
            .copy(inputs = mapOf("stepExecutionId" to "old-execution"))
        every { attempts.findLatestForStep(scope, "ns", "wf", "review") } returns latest.copy(contextEnvelope = envelope.toJson())
        assertThat(service.advance(scope, "ns", "wf", listOf(step), "/repo", null)).isFalse()
        verify(exactly = 0) { submissions.submit(any(), any(), any(), any(), any(), any()) }
    }

    @Test
    fun `stale execution cannot publish into replacement generation`() {
        fixture()
        assertThatThrownBy { service.record(scope, "ns", "wf", "review", "old-exec", "completed") }
            .hasMessageContaining("EXECUTION_GENERATION_FENCED")
        verify(exactly = 0) { repository.updateInstance(any(), any(), any(), any(), any()) }
    }
}
