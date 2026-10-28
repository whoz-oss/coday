package io.whozoss.factory.planchange

import io.whozoss.factory.error.FactoryException
import io.whozoss.factory.planchange.domain.DependencyChange
import io.whozoss.factory.planchange.domain.DependencyOp
import io.whozoss.factory.planchange.domain.PlanChangeProposalType
import io.whozoss.factory.planchange.domain.PlanChangeSubmitCommand
import io.whozoss.factory.planchange.domain.PlanChangeValidation
import io.whozoss.factory.planchange.domain.ScopeChange
import io.whozoss.factory.planchange.domain.ScopeChangeOp
import io.whozoss.factory.planchange.web.PlanChangeBounds
import org.assertj.core.api.Assertions.assertThat
import org.assertj.core.api.Assertions.assertThatCode
import org.assertj.core.api.Assertions.assertThatThrownBy
import org.junit.jupiter.api.Test

/**
 * Unit proofs of the bounded plan-change payload validation: required fields,
 * length/count caps and strict type/shape coherence reject with a 400
 * `INVALID_PLAN_CHANGE_PROPOSAL`; a valid payload is accepted.
 *
 * Pure unit tests — no Spring context, no I/O.
 */
class PlanChangeValidationTest {

    private fun validCommand(
        type: PlanChangeProposalType = PlanChangeProposalType.RETRY,
        dependencyChanges: List<DependencyChange> = emptyList(),
        scopeChanges: List<ScopeChange>? = null,
        affectedStepIds: List<String> = listOf("step-a"),
        summary: String = "bounded summary",
        reasonCode: String = "ORACLE_FAILURE",
        evidenceRefs: List<String> = listOf("evidence-1"),
        idempotencyKey: String = "key-1",
        namespaceId: String = "ns-1",
    ): PlanChangeSubmitCommand =
        PlanChangeSubmitCommand(
            workflowId = "wf-1",
            namespaceId = namespaceId,
            expectedRevision = 1,
            reasonCode = reasonCode,
            summary = summary,
            proposalType = type,
            affectedStepIds = affectedStepIds,
            proposedDependencyChanges = dependencyChanges,
            proposedScopeChanges = scopeChanges,
            evidenceRefs = evidenceRefs,
            idempotencyKey = idempotencyKey,
        )

    @Test
    fun `a valid payload is accepted`() {
        assertThatCode { PlanChangeValidation.validateSubmit(validCommand()) }
            .doesNotThrowAnyException()

        val dependencyProposal = validCommand(
            type = PlanChangeProposalType.DEPENDENCY,
            dependencyChanges = listOf(DependencyChange(DependencyOp.ADD, "step-a", "step-b")),
        )
        assertThatCode { PlanChangeValidation.validateSubmit(dependencyProposal) }
            .doesNotThrowAnyException()

        val scopeProposal = validCommand(
            type = PlanChangeProposalType.SCOPE,
            scopeChanges = listOf(ScopeChange(ScopeChangeOp.EXPAND, "work-items", "widen the batch")),
        )
        assertThatCode { PlanChangeValidation.validateSubmit(scopeProposal) }
            .doesNotThrowAnyException()
    }

    @Test
    fun `an over-long summary is rejected`() {
        val command = validCommand(summary = "x".repeat(PlanChangeBounds.MAX_SUMMARY + 1))
        assertInvalidProposal { PlanChangeValidation.validateSubmit(command) }
    }

    @Test
    fun `too many affected steps are rejected`() {
        val command = validCommand(
            affectedStepIds = (1..PlanChangeBounds.MAX_AFFECTED_STEPS + 1).map { "step-$it" },
        )
        assertInvalidProposal { PlanChangeValidation.validateSubmit(command) }
    }

    @Test
    fun `too many dependency changes are rejected`() {
        val changes = (1..PlanChangeBounds.MAX_DEPENDENCY_CHANGES + 1).map {
            DependencyChange(DependencyOp.ADD, "step-$it", "step-${it + 1000}")
        }
        val command = validCommand(type = PlanChangeProposalType.DEPENDENCY, dependencyChanges = changes)
        assertInvalidProposal { PlanChangeValidation.validateSubmit(command) }
    }

    @Test
    fun `too many evidence references are rejected`() {
        val command = validCommand(
            evidenceRefs = (1..PlanChangeBounds.MAX_EVIDENCE_REFS + 1).map { "evidence-$it" },
        )
        assertInvalidProposal { PlanChangeValidation.validateSubmit(command) }
    }

    @Test
    fun `a blank or over-long idempotency key is rejected`() {
        assertInvalidProposal { PlanChangeValidation.validateSubmit(validCommand(idempotencyKey = " ")) }
        assertInvalidProposal {
            PlanChangeValidation.validateSubmit(
                validCommand(idempotencyKey = "k".repeat(PlanChangeBounds.MAX_IDEMPOTENCY_KEY + 1)),
            )
        }
    }

    @Test
    fun `a non-positive expected revision is rejected`() {
        val command = validCommand().copy(expectedRevision = 0)
        assertInvalidProposal { PlanChangeValidation.validateSubmit(command) }
    }

    @Test
    fun `a missing namespace is an INVALID_NAMESPACE_ID`() {
        val command = validCommand(namespaceId = "")
        assertThatThrownBy { PlanChangeValidation.validateSubmit(command) }
            .isInstanceOf(FactoryException::class.java)
            .satisfies({ error ->
                val factoryError = error as FactoryException
                assertThat(factoryError.statusCode).isEqualTo(400)
                assertThat(factoryError.errorCode).isEqualTo("INVALID_NAMESPACE_ID")
            })
    }

    @Test
    fun `a RETRY proposal cannot smuggle dependency or scope changes`() {
        val withDependency = validCommand(
            dependencyChanges = listOf(DependencyChange(DependencyOp.ADD, "step-a", "step-b")),
        )
        assertInvalidProposal { PlanChangeValidation.validateSubmit(withDependency) }

        val withScope = validCommand(scopeChanges = listOf(ScopeChange(ScopeChangeOp.EXPAND, "steps")))
        assertInvalidProposal { PlanChangeValidation.validateSubmit(withScope) }
    }

    @Test
    fun `a DEPENDENCY proposal requires dependency changes and rejects scope changes`() {
        val empty = validCommand(type = PlanChangeProposalType.DEPENDENCY)
        assertInvalidProposal { PlanChangeValidation.validateSubmit(empty) }

        val mixed = validCommand(
            type = PlanChangeProposalType.DEPENDENCY,
            dependencyChanges = listOf(DependencyChange(DependencyOp.ADD, "step-a", "step-b")),
            scopeChanges = listOf(ScopeChange(ScopeChangeOp.MODIFY, "steps")),
        )
        assertInvalidProposal { PlanChangeValidation.validateSubmit(mixed) }
    }

    @Test
    fun `a RETRY proposal must reference at least one affected step`() {
        val command = validCommand(affectedStepIds = emptyList())
        assertInvalidProposal { PlanChangeValidation.validateSubmit(command) }
    }

    @Test
    fun `a self-referencing dependency change is rejected`() {
        val command = validCommand(
            type = PlanChangeProposalType.DEPENDENCY,
            dependencyChanges = listOf(DependencyChange(DependencyOp.ADD, "step-a", "step-a")),
        )
        assertInvalidProposal { PlanChangeValidation.validateSubmit(command) }
    }

    private fun assertInvalidProposal(block: () -> Unit) {
        assertThatThrownBy(block)
            .isInstanceOf(FactoryException::class.java)
            .satisfies({ error ->
                val factoryError = error as FactoryException
                assertThat(factoryError.statusCode).isEqualTo(400)
                assertThat(factoryError.errorCode).isEqualTo("INVALID_PLAN_CHANGE_PROPOSAL")
            })
    }
}
