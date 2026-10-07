package io.whozoss.factory.planchange

import io.whozoss.factory.error.FactoryException
import io.whozoss.factory.planchange.domain.GovernanceGateEvaluator
import io.whozoss.factory.planchange.domain.PlanChangeDecisionStatus
import io.whozoss.factory.planchange.domain.PlanChangeKind
import org.assertj.core.api.Assertions.assertThat
import org.assertj.core.api.Assertions.assertThatCode
import org.assertj.core.api.Assertions.assertThatThrownBy
import org.junit.jupiter.api.Test

/**
 * Unit proofs of the governance Rules 1–3 encoded in [GovernanceGateEvaluator]:
 *  - Rule 1 pre-declared variations recommend and permit `AUTO_APPLIED`;
 *  - Rule 2 structural / scope / contract changes never auto-apply — they require
 *    a human gate or a new definition projection;
 *  - `REJECTED`, `GATE_REQUIRED` and `REQUIRES_NEW_DEFINITION` are always
 *    recordable; `AUTO_APPLIED` on a Rule-2 kind is a 409 `PLAN_CHANGE_GATE_REQUIRED`.
 *
 * Pure unit tests — no Spring context, no I/O.
 */
class GovernanceGateEvaluatorTest {

    @Test
    fun `Rule 1 kinds recommend AUTO_APPLIED`() {
        assertThat(GovernanceGateEvaluator.recommendedVerdict(PlanChangeKind.RETRY_NO_PLAN_CHANGE))
            .isEqualTo(PlanChangeDecisionStatus.AUTO_APPLIED)
        assertThat(GovernanceGateEvaluator.recommendedVerdict(PlanChangeKind.PATH_SELECTION))
            .isEqualTo(PlanChangeDecisionStatus.AUTO_APPLIED)
        assertThat(GovernanceGateEvaluator.recommendedVerdict(PlanChangeKind.OPTIONAL_STEP_ACTIVATION))
            .isEqualTo(PlanChangeDecisionStatus.AUTO_APPLIED)
    }

    @Test
    fun `Rule 2 dependency and scope kinds recommend GATE_REQUIRED`() {
        assertThat(GovernanceGateEvaluator.recommendedVerdict(PlanChangeKind.DEPENDENCY_CHANGE_PROPOSAL))
            .isEqualTo(PlanChangeDecisionStatus.GATE_REQUIRED)
        assertThat(GovernanceGateEvaluator.recommendedVerdict(PlanChangeKind.SCOPE_CHANGE_PROPOSAL))
            .isEqualTo(PlanChangeDecisionStatus.GATE_REQUIRED)
    }

    @Test
    fun `Rule 2 new-step and contract-or-oracle kinds recommend REQUIRES_NEW_DEFINITION`() {
        assertThat(GovernanceGateEvaluator.recommendedVerdict(PlanChangeKind.NEW_STEP_PROPOSAL))
            .isEqualTo(PlanChangeDecisionStatus.REQUIRES_NEW_DEFINITION)
        assertThat(GovernanceGateEvaluator.recommendedVerdict(PlanChangeKind.CONTRACT_OR_ORACLE_CHANGE_PROPOSAL))
            .isEqualTo(PlanChangeDecisionStatus.REQUIRES_NEW_DEFINITION)
    }

    @Test
    fun `AUTO_APPLIED is permitted for Rule 1 self-applicable kinds`() {
        GovernanceGateEvaluator.SELF_APPLICABLE_KINDS.forEach { kind ->
            assertThatCode {
                GovernanceGateEvaluator.assertDecisionAllowed(kind, PlanChangeDecisionStatus.AUTO_APPLIED)
            }.doesNotThrowAnyException()
        }
    }

    @Test
    fun `AUTO_APPLIED on a Rule 2 kind is a 409 PLAN_CHANGE_GATE_REQUIRED`() {
        val gated = PlanChangeKind.entries - GovernanceGateEvaluator.SELF_APPLICABLE_KINDS
        assertThat(gated).containsExactlyInAnyOrder(
            PlanChangeKind.DEPENDENCY_CHANGE_PROPOSAL,
            PlanChangeKind.SCOPE_CHANGE_PROPOSAL,
            PlanChangeKind.NEW_STEP_PROPOSAL,
            PlanChangeKind.CONTRACT_OR_ORACLE_CHANGE_PROPOSAL,
        )
        gated.forEach { kind ->
            assertThatThrownBy {
                GovernanceGateEvaluator.assertDecisionAllowed(kind, PlanChangeDecisionStatus.AUTO_APPLIED)
            }
                .isInstanceOf(FactoryException::class.java)
                .satisfies({ error ->
                    val factoryError = error as FactoryException
                    assertThat(factoryError.statusCode).isEqualTo(409)
                    assertThat(factoryError.errorCode).isEqualTo("PLAN_CHANGE_GATE_REQUIRED")
                })
        }
    }

    @Test
    fun `REJECTED is recordable for every kind`() {
        PlanChangeKind.entries.forEach { kind ->
            assertThatCode {
                GovernanceGateEvaluator.assertDecisionAllowed(kind, PlanChangeDecisionStatus.REJECTED)
            }.doesNotThrowAnyException()
        }
    }

    @Test
    fun `GATE_REQUIRED and REQUIRES_NEW_DEFINITION are recordable for every kind`() {
        PlanChangeKind.entries.forEach { kind ->
            assertThatCode {
                GovernanceGateEvaluator.assertDecisionAllowed(kind, PlanChangeDecisionStatus.GATE_REQUIRED)
                GovernanceGateEvaluator.assertDecisionAllowed(kind, PlanChangeDecisionStatus.REQUIRES_NEW_DEFINITION)
            }.doesNotThrowAnyException()
        }
    }

    @Test
    fun `PENDING_VALIDATION is not a recordable decision`() {
        PlanChangeKind.entries.forEach { kind ->
            assertThatThrownBy {
                GovernanceGateEvaluator.assertDecisionAllowed(kind, PlanChangeDecisionStatus.PENDING_VALIDATION)
            }.isInstanceOf(IllegalArgumentException::class.java)
        }
    }
}
