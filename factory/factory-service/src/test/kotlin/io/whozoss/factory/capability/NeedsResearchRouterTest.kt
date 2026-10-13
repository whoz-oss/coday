package io.whozoss.factory.capability

import io.whozoss.factory.agentattempt.domain.AgentAttemptStatus
import io.whozoss.factory.agentattempt.domain.DurableAgentAttempt
import io.whozoss.factory.agentattempt.service.DurableAgentAttemptService
import io.whozoss.factory.persistence.TenantScope
import io.whozoss.factory.workflow.domain.WorkflowEvidenceItem
import io.whozoss.factory.workflow.domain.WorkflowStepDefinition
import io.whozoss.factory.workflow.domain.WorkflowStepResponsibility
import io.whozoss.factory.workflow.domain.ResponsibilityKind
import io.whozoss.factory.workflow.persistence.EvidenceAppendResult
import io.whozoss.factory.workflow.persistence.WorkflowEvidenceRepository
import io.mockk.every
import io.mockk.mockk
import io.mockk.slot
import io.mockk.verify
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test

/**
 * Pure unit tests of the automatic NEEDS_RESEARCH → Searcher routing (Lot E).
 *
 * The Searcher turn is injected as a callback, so no AgentOS transport is
 * required: the tests pin the orchestration contract — the original proof is
 * preserved, the initial step is re-armed on the SAME worktree only once the
 * Searcher succeeded, and nothing is ever converted into a terminal failure.
 */
class NeedsResearchRouterTest {

    private val scope = TenantScope("org", "ws")
    private val namespace = "ns"
    private val workflowId = "wf"
    private val attempts = mockk<DurableAgentAttemptService>()
    private val evidence = mockk<WorkflowEvidenceRepository>()
    private val router = NeedsResearchRouter(attempts, evidence)

    private val step = WorkflowStepDefinition(
        id = "A",
        name = "Implement",
        responsibility = WorkflowStepResponsibility(ResponsibilityKind.AGENT, "architect"),
        dependsOn = emptyList(),
    )

    private val result = CapabilityOutcome.AgentNeedsResearch(
        stepId = "A",
        persona = "architect",
        attemptId = "attempt-1",
        resultId = "result-1",
        summary = "missing upstream contract",
        findings = listOf(mapOf("severity" to "blocking", "code" to "MISSING")),
        artifacts = listOf(mapOf("kind" to "report", "encoding" to "markdown", "content" to "# blocked")),
        claims = mapOf("modifiedFiles" to listOf("libs/a.ts")),
    )

    private val predecessor = DurableAgentAttempt(
        attemptId = "attempt-1",
        caseId = "case-A",
        namespaceId = namespace,
        workflowId = workflowId,
        stepId = "A",
        attemptNumber = 1,
        agentName = "architect",
        rootCaseId = "root-case",
        parentCaseId = "root-case",
        status = AgentAttemptStatus.INDETERMINATE,
    )

    private fun stubEvidenceAppend(): MutableList<WorkflowEvidenceItem> {
        val captured = mutableListOf<WorkflowEvidenceItem>()
        val slot = slot<WorkflowEvidenceItem>()
        every { evidence.append(scope, namespace, workflowId, capture(slot)) } answers {
            captured.add(slot.captured)
            EvidenceAppendResult.Created(slot.captured)
        }
        return captured
    }

    @Test
    fun `preserves the original proof and re-arms the step on the same worktree after a successful search`() {
        val persisted = stubEvidenceAppend()
        every { attempts.find(scope, namespace, workflowId, "A", "attempt-1") } returns predecessor
        every { attempts.nextAttemptNumber(scope, namespace, workflowId, "A") } returns 2
        val registered = slot<DurableAgentAttempt>()
        every { attempts.registerRetry(scope, capture(registered), any()) } answers { registered.captured }

        val outcome = router.route(scope, namespace, workflowId, step, result) { searcherStep ->
            assertThat(searcherStep.responsibility.name).isEqualTo(NeedsResearchRouter.SEARCHER_AGENT_NAME)
            CapabilityExecution(
                outcome = CapabilityOutcome.AgentCompleted(searcherStep.id, "Searcher", "PASS", emptyMap()),
                attemptId = "searcher-attempt",
            )
        }

        assertThat(outcome.reArmed).isTrue()
        assertThat(outcome.searcherSucceeded).isTrue()
        assertThat(outcome.searcherAttemptId).isEqualTo("searcher-attempt")
        assertThat(outcome.reArmedAttemptId)
            .isEqualTo(CapabilityExecutionService.retryAttemptId(workflowId, "A", 2))

        // The proof is preserved verbatim, never rewritten.
        assertThat(persisted).hasSize(1)
        val proof = persisted.single()
        assertThat(proof.kind).isEqualTo(NeedsResearchRouter.NEEDS_RESEARCH_EVIDENCE_KIND)
        assertThat(proof.outcome).isEqualTo(NeedsResearchRouter.NEEDS_RESEARCH_OUTCOME)
        assertThat(proof.facts["findings"]).isEqualTo(result.findings)
        assertThat(proof.facts["artifacts"]).isEqualTo(result.artifacts)
        assertThat(proof.facts["claims"]).isEqualTo(result.claims)

        // The re-armed attempt continues the SAME case family (worktree / sub-case).
        val successor = registered.captured
        assertThat(successor.attemptNumber).isEqualTo(2)
        assertThat(successor.caseId).isEqualTo("case-A")
        assertThat(successor.rootCaseId).isEqualTo("root-case")
        assertThat(successor.parentCaseId).isEqualTo("root-case")
        assertThat(successor.resumptionContext).contains("NEEDS_RESEARCH")
    }

    @Test
    fun `a failed search preserves the proof but does not re-arm`() {
        val persisted = stubEvidenceAppend()

        val outcome = router.route(scope, namespace, workflowId, step, result) { searcherStep ->
            CapabilityExecution(
                outcome = CapabilityOutcome.AgentFailed(searcherStep.id, "Searcher", "AGENT_CASE_ERROR", "boom"),
                attemptId = "searcher-attempt",
            )
        }

        assertThat(outcome.reArmed).isFalse()
        assertThat(outcome.searcherSucceeded).isFalse()
        assertThat(outcome.reArmedAttemptId).isNull()
        assertThat(persisted).hasSize(1)
        verify(exactly = 0) { attempts.registerRetry(any(), any(), any()) }
    }
}
