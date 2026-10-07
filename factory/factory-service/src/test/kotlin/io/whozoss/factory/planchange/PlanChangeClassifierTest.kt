package io.whozoss.factory.planchange

import io.whozoss.factory.planchange.domain.DependencyChange
import io.whozoss.factory.planchange.domain.DependencyOp
import io.whozoss.factory.planchange.domain.PlanChangeClassifier
import io.whozoss.factory.planchange.domain.PlanChangeKind
import io.whozoss.factory.planchange.domain.PlanChangeProposalType
import io.whozoss.factory.planchange.domain.PlanChangeSubmitCommand
import io.whozoss.factory.planchange.domain.ScopeChange
import io.whozoss.factory.planchange.domain.ScopeChangeOp
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test

/**
 * Unit proofs of the deterministic plan-change classification: the same payload
 * always yields the same [PlanChangeKind], the declared type maps to the expected
 * taxonomy value for all seven cases, and a structural payload can never be
 * downgraded by a benign declared type.
 *
 * Pure unit tests — no Spring context, no I/O.
 */
class PlanChangeClassifierTest {

    private fun command(
        type: PlanChangeProposalType,
        dependencyChanges: List<DependencyChange> = emptyList(),
        scopeChanges: List<ScopeChange>? = null,
    ): PlanChangeSubmitCommand =
        PlanChangeSubmitCommand(
            workflowId = "wf-1",
            namespaceId = "ns-1",
            expectedRevision = 1,
            reasonCode = "TEST_REASON",
            summary = "classification probe",
            proposalType = type,
            affectedStepIds = listOf("step-a"),
            proposedDependencyChanges = dependencyChanges,
            proposedScopeChanges = scopeChanges,
            evidenceRefs = emptyList(),
            idempotencyKey = "key-1",
        )

    @Test
    fun `a declared retry classifies as RETRY_NO_PLAN_CHANGE`() {
        assertThat(PlanChangeClassifier.classify(command(PlanChangeProposalType.RETRY)))
            .isEqualTo(PlanChangeKind.RETRY_NO_PLAN_CHANGE)
    }

    @Test
    fun `a declared pathway selection classifies as PATH_SELECTION`() {
        assertThat(PlanChangeClassifier.classify(command(PlanChangeProposalType.PATH_SELECTION)))
            .isEqualTo(PlanChangeKind.PATH_SELECTION)
    }

    @Test
    fun `a declared optional step activation classifies as OPTIONAL_STEP_ACTIVATION`() {
        assertThat(PlanChangeClassifier.classify(command(PlanChangeProposalType.OPTIONAL_STEP)))
            .isEqualTo(PlanChangeKind.OPTIONAL_STEP_ACTIVATION)
    }

    @Test
    fun `a declared dependency change classifies as DEPENDENCY_CHANGE_PROPOSAL`() {
        val changes = listOf(DependencyChange(DependencyOp.ADD, "step-a", "step-b"))
        assertThat(
            PlanChangeClassifier.classify(command(PlanChangeProposalType.DEPENDENCY, dependencyChanges = changes)),
        ).isEqualTo(PlanChangeKind.DEPENDENCY_CHANGE_PROPOSAL)
    }

    @Test
    fun `a declared scope change classifies as SCOPE_CHANGE_PROPOSAL`() {
        val changes = listOf(ScopeChange(ScopeChangeOp.EXPAND, "work-items"))
        assertThat(
            PlanChangeClassifier.classify(command(PlanChangeProposalType.SCOPE, scopeChanges = changes)),
        ).isEqualTo(PlanChangeKind.SCOPE_CHANGE_PROPOSAL)
    }

    @Test
    fun `a declared new step classifies as NEW_STEP_PROPOSAL`() {
        assertThat(PlanChangeClassifier.classify(command(PlanChangeProposalType.NEW_STEP)))
            .isEqualTo(PlanChangeKind.NEW_STEP_PROPOSAL)
    }

    @Test
    fun `a declared contract or oracle change classifies as CONTRACT_OR_ORACLE_CHANGE_PROPOSAL`() {
        assertThat(PlanChangeClassifier.classify(command(PlanChangeProposalType.CONTRACT_OR_ORACLE)))
            .isEqualTo(PlanChangeKind.CONTRACT_OR_ORACLE_CHANGE_PROPOSAL)
    }

    @Test
    fun `classification is deterministic — same input twice yields the identical kind`() {
        val changes = listOf(DependencyChange(DependencyOp.REMOVE, "step-a", "step-b"))
        val input = command(PlanChangeProposalType.DEPENDENCY, dependencyChanges = changes)
        val first = PlanChangeClassifier.classify(input)
        val second = PlanChangeClassifier.classify(input)
        assertThat(first).isEqualTo(second)
        assertThat(first).isEqualTo(PlanChangeKind.DEPENDENCY_CHANGE_PROPOSAL)
    }

    @Test
    fun `a structural payload cannot be downgraded by a benign declared type`() {
        val dependencyChanges = listOf(DependencyChange(DependencyOp.ADD, "step-a", "step-b"))
        assertThat(
            PlanChangeClassifier.classify(command(PlanChangeProposalType.RETRY, dependencyChanges = dependencyChanges)),
        ).isEqualTo(PlanChangeKind.DEPENDENCY_CHANGE_PROPOSAL)

        val scopeChanges = listOf(ScopeChange(ScopeChangeOp.EXPAND, "steps"))
        assertThat(
            PlanChangeClassifier.classify(command(PlanChangeProposalType.PATH_SELECTION, scopeChanges = scopeChanges)),
        ).isEqualTo(PlanChangeKind.SCOPE_CHANGE_PROPOSAL)
    }

    @Test
    fun `the most structural signal wins between dependency and scope shapes`() {
        val input = command(
            PlanChangeProposalType.SCOPE,
            dependencyChanges = listOf(DependencyChange(DependencyOp.ADD, "step-a", "step-b")),
            scopeChanges = listOf(ScopeChange(ScopeChangeOp.MODIFY, "steps")),
        )
        assertThat(PlanChangeClassifier.classify(input)).isEqualTo(PlanChangeKind.DEPENDENCY_CHANGE_PROPOSAL)
    }
}
