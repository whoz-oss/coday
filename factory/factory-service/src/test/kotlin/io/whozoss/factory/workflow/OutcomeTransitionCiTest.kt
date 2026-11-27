package io.whozoss.factory.workflow

import io.mockk.every
import io.mockk.mockk
import io.mockk.verify
import io.whozoss.factory.agentattempt.service.AgentStepResultService
import io.whozoss.factory.agentattempt.service.DurableAgentAttemptService
import io.whozoss.factory.persistence.TenantScope
import io.whozoss.factory.workflow.domain.*
import io.whozoss.factory.workflow.persistence.*
import io.whozoss.factory.workflow.service.*
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test

/** The `github-pr-checks` code step returns to its target only for a REAL red CI verdict of its current execution. */
class OutcomeTransitionCiTest {
    private val repository = mockk<WorkflowRepository>(relaxed = true)
    private val evidence = mockk<WorkflowEvidenceRepository>(relaxed = true)
    private val interactions = mockk<HumanInteractionRepository>(relaxed = true)
    private val attempts = mockk<DurableAgentAttemptService>(relaxed = true)
    private val results = mockk<AgentStepResultService>(relaxed = true)
    private val submissions = mockk<SessionRunSubmissionService>(relaxed = true)
    private val service = OutcomeTransitionService(repository, evidence, interactions, attempts, results, submissions)
    private val scope = TenantScope("org", "stream")

    private val design = WorkflowStepDefinition("design", "Design", WorkflowStepResponsibility(ResponsibilityKind.AGENT, "Architect"), emptyList())
    private val ci = WorkflowStepDefinition(
        "ci", "CI", WorkflowStepResponsibility(ResponsibilityKind.CODE, "github-pr-checks"), listOf("design"),
        outcomeTransitions = OutcomeTransitions(fail = OutcomeAction("return", "design")),
    )
    private val steps = listOf(design, ci)

    private fun execution(step: String, id: String, status: String) = mapOf(
        "executionId" to id, "stepId" to step, "executionNumber" to 1, "cycleNumber" to 0, "status" to status,
    )

    private var instance = WorkflowInstanceRecord(
        "ns", "wf", 1, "active", null,
        mapOf("status" to "running", "definitionVersion" to "1.0.0", "definitionHash" to "hash"),
        mapOf(
            "schemaVersion" to "2",
            "steps" to listOf(mapOf("id" to "design", "status" to "completed"), mapOf("id" to "ci", "status" to "failed")),
            "stepExecutions" to listOf(execution("design", "exec-design", "completed"), execution("ci", "exec-ci", "failed")),
        ),
    )

    private fun ciEvidence(outcome: String = "fail", verdict: String = "failed", executionId: String = "exec-ci") = WorkflowEvidenceItem(
        "ev-ci", "ns", "wf", "ci", "ci-checks", outcome, mapOf("kind" to "factory-ci-checks"),
        mapOf(
            "stepId" to "ci", "executionId" to executionId, "verdict" to verdict, "prNumber" to 7, "headSha" to "abcdef0123456789",
            "failedChecks" to listOf(
                mapOf("name" to "validate-jvm", "conclusion" to "failure", "url" to "https://ci/1", "summary" to "2 tests failed", "logExcerpt" to "FAILED SomeTest"),
            ),
        ),
        null, null,
    )

    private fun fixture(vararg items: WorkflowEvidenceItem) {
        every { repository.findInstance(scope, "ns", "wf") } answers { instance }
        every { repository.findStepStates(scope, "ns", "wf") } returns listOf(
            WorkflowStepStateRecord("ns", "wf", "design", 1, "completed", mapOf("execution" to execution("design", "exec-design", "completed"))),
            WorkflowStepStateRecord("ns", "wf", "ci", 1, "failed", mapOf("execution" to execution("ci", "exec-ci", "failed"))),
        )
        every { evidence.list(any(), any(), any(), any()) } returns items.toList()
        every { evidence.list(any(), any(), any()) } returns items.toList()
        every { attempts.findByWorkflow(scope, "ns", "wf") } returns emptyList()
        every { repository.updateInstance(scope, "ns", "wf", any(), any()) } answers { instance = arg(4); true }
        every { repository.publishProjection(any(), any(), any()) } answers { ProjectionPublishResult.Changed(arg(1)) }
        every { evidence.append(any(), any(), any(), any()) } answers { EvidenceAppendResult.Created(arg(3)) }
    }

    @Test
    fun `a red CI verdict of the current execution creates one cycle with the failed checks and logs`() {
        fixture(ciEvidence())
        assertThat(service.advance(scope, "ns", "wf", steps, "/repo", null)).isTrue()

        val correction = instance.projection["correction"] as Map<*, *>
        assertThat(correction["cycleNumber"]).isEqualTo(1)
        assertThat(correction["returnTargetStepId"]).isEqualTo("design")
        assertThat(correction["resultId"]).isEqualTo("ci:ev-ci")
        val context = instance.projection["correctionContext"] as Map<*, *>
        assertThat(context["summary"].toString()).contains("PR #7", "validate-jvm")
        assertThat((context["findings"] as List<*>).single().toString()).contains("CI_CHECK_FAILED")
        val artifact = (context["artifacts"] as List<*>).single() as Map<*, *>
        assertThat(artifact["kind"]).isEqualTo("ci-failure-logs")
        assertThat(artifact["encoding"]).isEqualTo("markdown")
        assertThat(artifact["content"].toString()).contains("FAILED SomeTest", "https://ci/1")
        assertThat(context["instruction"].toString()).contains("CI of the pull request failed")
        // The target and the CI step are replayed as brand-new executions.
        assertThat((instance.projection["stepExecutions"] as List<*>)).hasSize(4)
        verify(exactly = 1) { submissions.submit(scope, "ns", "wf", "/repo", any(), null) }
        // Replay of the same advance never opens a second cycle for the same verdict.
        assertThat(service.advance(scope, "ns", "wf", steps, "/repo", null)).isFalse()
    }

    @Test
    fun `an exhausted budget waits for a human instead of returning`() {
        fixture(ciEvidence())
        val region = CanonicalHash.sha256Hex(listOf("ci", "design").sorted().joinToString("\n"))
        instance = instance.copy(projection = instance.projection + ("correctionCounts" to mapOf(region to 2)))
        assertThat(service.advance(scope, "ns", "wf", steps, "/repo", null)).isTrue()
        assertThat((instance.projection["correction"] as Map<*, *>)["status"]).isEqualTo("waiting_human")
        verify(exactly = 1) { interactions.insert(scope, match { it.interactionType == "correction_exhausted" }) }
        verify(exactly = 0) { submissions.submit(any(), any(), any(), any(), any(), any()) }
    }

    @Test
    fun `a timeout or a refusal leaves no CI verdict and never returns`() {
        // POLL_TIMEOUT_EXCEEDED and every refusal (no token, draft PR, no PR, 401) record no failed ci-checks verdict.
        fixture()
        assertThat(service.advance(scope, "ns", "wf", steps, "/repo", null)).isFalse()
        // A still-pending observation is not a verdict either.
        fixture(ciEvidence(outcome = "pending", verdict = "pending"))
        assertThat(service.advance(scope, "ns", "wf", steps, "/repo", null)).isFalse()
        verify(exactly = 0) { submissions.submit(any(), any(), any(), any(), any(), any()) }
    }

    @Test
    fun `a red verdict of an older execution never triggers the current one`() {
        fixture(ciEvidence(executionId = "exec-ci-previous"))
        assertThat(service.advance(scope, "ns", "wf", steps, "/repo", null)).isFalse()
        verify(exactly = 0) { submissions.submit(any(), any(), any(), any(), any(), any()) }
    }

    @Test
    fun `a polling step in the correction scope blocks the replay`() {
        fixture(ciEvidence())
        every { repository.findStepStates(scope, "ns", "wf") } returns listOf(
            WorkflowStepStateRecord("ns", "wf", "design", 1, "waiting_external", mapOf("execution" to execution("design", "exec-design", "waiting_external"))),
            WorkflowStepStateRecord("ns", "wf", "ci", 1, "failed", mapOf("execution" to execution("ci", "exec-ci", "failed"))),
        )
        org.assertj.core.api.Assertions.assertThatThrownBy { service.advance(scope, "ns", "wf", steps, "/repo", null) }
            .hasMessageContaining("CORRECTION_SCOPE_ACTIVE")
    }
}
