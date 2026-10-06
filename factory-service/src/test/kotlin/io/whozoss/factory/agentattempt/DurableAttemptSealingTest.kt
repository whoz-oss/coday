package io.whozoss.factory.agentattempt

import io.whozoss.factory.Neo4jDomainIntegrationTest
import io.whozoss.factory.agentattempt.domain.AgentAttemptStatus
import io.whozoss.factory.agentattempt.domain.AttemptLeaseFencingException
import io.whozoss.factory.agentattempt.domain.DurableAgentAttempt
import io.whozoss.factory.agentattempt.domain.IdempotencyKeyCollisionException
import io.whozoss.factory.agentattempt.domain.InvalidAttemptTransitionException
import io.whozoss.factory.agentattempt.service.DurableAgentAttemptService
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Assertions.assertThrows
import org.junit.jupiter.api.Test
import org.springframework.beans.factory.annotation.Autowired

/**
 * Phase 10 attestation: a terminal durable attempt is SEALED and immutable.
 *
 * Terminal statuses (`succeeded`, `failed`, `indeterminate`, `interrupted`,
 * `superseded`) admit no outgoing transition in
 * `factory-service/src/main/kotlin/io/whozoss/factory/agentattempt/domain/AgentAttemptStatus.kt`,
 * and the Neo4j CAS statements of
 * `factory-service/src/main/kotlin/io/whozoss/factory/agentattempt/persistence/SpringDataNeo4jDurableAgentAttemptRepository.kt`
 * match nothing on a terminal node, so:
 *
 *  - any late capability redemption or late finalization targeting a sealed
 *    attempt is REJECTED (never silently mutates it);
 *  - only an idempotent replay of the SAME terminal verdict by the SAME owner
 *    returns the existing record, byte-for-byte unchanged;
 *  - a retry never reactivates attempt `N`: it registers a brand-new
 *    `attemptId` with `attemptNumber = N + 1` while `N` stays sealed.
 */
class DurableAttemptSealingTest : Neo4jDomainIntegrationTest() {

    @Autowired
    private lateinit var service: DurableAgentAttemptService

    @Test
    fun `a sealed attempt rejects every late mutation and replays only its own verdict idempotently`() {
        service.register(scope, attempt("attempt-sealed"))
        claimToRunning("attempt-sealed", "owner-a")
        val sealed = service.finalize(
            scope,
            namespaceId = NAMESPACE_ID,
            workflowId = WORKFLOW_ID,
            stepId = STEP_ID,
            attemptId = "attempt-sealed",
            ownerToken = "owner-a",
            target = AgentAttemptStatus.SUCCEEDED,
            resultEvidenceId = "evidence-sealed",
        )
        assertThat(sealed.status).isEqualTo(AgentAttemptStatus.SUCCEEDED)

        // A late divergent finalization by the same owner is rejected as an
        // invalid transition: the sealed verdict never flips.
        val divergent = assertThrows(InvalidAttemptTransitionException::class.java) {
            service.finalize(
                scope,
                namespaceId = NAMESPACE_ID,
                workflowId = WORKFLOW_ID,
                stepId = STEP_ID,
                attemptId = "attempt-sealed",
                ownerToken = "owner-a",
                target = AgentAttemptStatus.FAILED,
            )
        }
        assertThat(divergent.errorCode).isEqualTo("ATTEMPT_INVALID_TRANSITION")

        // A late finalization carrying a diverged lease token is fenced out.
        val fenced = assertThrows(AttemptLeaseFencingException::class.java) {
            service.finalize(
                scope,
                namespaceId = NAMESPACE_ID,
                workflowId = WORKFLOW_ID,
                stepId = STEP_ID,
                attemptId = "attempt-sealed",
                ownerToken = "owner-b",
                target = AgentAttemptStatus.SUCCEEDED,
            )
        }
        assertThat(fenced.errorCode).isEqualTo("ATTEMPT_LEASE_FENCED")

        // A late intermediate transition out of the sealed state is rejected.
        val transition = assertThrows(InvalidAttemptTransitionException::class.java) {
            service.transition(
                scope,
                namespaceId = NAMESPACE_ID,
                workflowId = WORKFLOW_ID,
                stepId = STEP_ID,
                attemptId = "attempt-sealed",
                ownerToken = "owner-a",
                target = AgentAttemptStatus.RUNNING,
            )
        }
        assertThat(transition.errorCode).isEqualTo("ATTEMPT_INVALID_TRANSITION")

        // Only the idempotent replay of the SAME verdict by the SAME owner is
        // accepted, and it changes nothing.
        val replayed = service.finalize(
            scope,
            namespaceId = NAMESPACE_ID,
            workflowId = WORKFLOW_ID,
            stepId = STEP_ID,
            attemptId = "attempt-sealed",
            ownerToken = "owner-a",
            target = AgentAttemptStatus.SUCCEEDED,
            resultEvidenceId = "evidence-sealed",
        )
        assertThat(replayed.status).isEqualTo(AgentAttemptStatus.SUCCEEDED)
        assertThat(replayed.revision).isEqualTo(sealed.revision)
        assertThat(replayed.completedAt).isEqualTo(sealed.completedAt)

        // The sealed record is byte-for-byte unchanged after every late call.
        val persisted = service.find(scope, NAMESPACE_ID, WORKFLOW_ID, STEP_ID, "attempt-sealed")
        assertThat(persisted!!.status).isEqualTo(AgentAttemptStatus.SUCCEEDED)
        assertThat(persisted.revision).isEqualTo(sealed.revision)
        assertThat(persisted.resultEvidenceId).isEqualTo("evidence-sealed")
        assertThat(persisted.completedAt).isEqualTo(sealed.completedAt)
        assertThat(persisted.ownerToken).isEqualTo("owner-a")
    }

    @Test
    fun `cancel and supersede cannot reopen a sealed attempt and replay only on their own terminal status`() {
        // A `succeeded` attempt rejects both cancellation and superseding.
        service.register(scope, attempt("attempt-cancel-sealed"))
        claimToRunning("attempt-cancel-sealed", "owner-a")
        service.finalize(
            scope,
            namespaceId = NAMESPACE_ID,
            workflowId = WORKFLOW_ID,
            stepId = STEP_ID,
            attemptId = "attempt-cancel-sealed",
            ownerToken = "owner-a",
            target = AgentAttemptStatus.SUCCEEDED,
        )
        val cancelSealed = assertThrows(InvalidAttemptTransitionException::class.java) {
            service.requestCancel(scope, NAMESPACE_ID, WORKFLOW_ID, STEP_ID, "attempt-cancel-sealed")
        }
        assertThat(cancelSealed.errorCode).isEqualTo("ATTEMPT_INVALID_TRANSITION")
        val supersedeSealed = assertThrows(InvalidAttemptTransitionException::class.java) {
            service.supersede(scope, NAMESPACE_ID, WORKFLOW_ID, STEP_ID, "attempt-cancel-sealed")
        }
        assertThat(supersedeSealed.errorCode).isEqualTo("ATTEMPT_INVALID_TRANSITION")

        // Cancellation seals as `interrupted` and replays idempotently, but a
        // supersede of the interrupted record is still an invalid transition.
        service.register(scope, attempt("attempt-interrupted"))
        claimToRunning("attempt-interrupted", "owner-a")
        val cancelled = service.requestCancel(scope, NAMESPACE_ID, WORKFLOW_ID, STEP_ID, "attempt-interrupted")
        assertThat(cancelled.status).isEqualTo(AgentAttemptStatus.INTERRUPTED)
        val cancelReplay = service.requestCancel(scope, NAMESPACE_ID, WORKFLOW_ID, STEP_ID, "attempt-interrupted")
        assertThat(cancelReplay.status).isEqualTo(AgentAttemptStatus.INTERRUPTED)
        assertThat(cancelReplay.revision).isEqualTo(cancelled.revision)
        val supersedeInterrupted = assertThrows(InvalidAttemptTransitionException::class.java) {
            service.supersede(scope, NAMESPACE_ID, WORKFLOW_ID, STEP_ID, "attempt-interrupted")
        }
        assertThat(supersedeInterrupted.errorCode).isEqualTo("ATTEMPT_INVALID_TRANSITION")

        // A `waiting_human` attempt seals as `superseded` exactly once; the
        // replay returns the sealed record and a cancellation cannot reopen it.
        service.register(scope, attempt("attempt-superseded"))
        claimToRunning("attempt-superseded", "owner-a")
        service.transition(
            scope,
            namespaceId = NAMESPACE_ID,
            workflowId = WORKFLOW_ID,
            stepId = STEP_ID,
            attemptId = "attempt-superseded",
            ownerToken = "owner-a",
            target = AgentAttemptStatus.WAITING_HUMAN,
        )
        val superseded = service.supersede(scope, NAMESPACE_ID, WORKFLOW_ID, STEP_ID, "attempt-superseded")
        assertThat(superseded.status).isEqualTo(AgentAttemptStatus.SUPERSEDED)
        val supersedeReplay = service.supersede(scope, NAMESPACE_ID, WORKFLOW_ID, STEP_ID, "attempt-superseded")
        assertThat(supersedeReplay.status).isEqualTo(AgentAttemptStatus.SUPERSEDED)
        assertThat(supersedeReplay.revision).isEqualTo(superseded.revision)
        val cancelSuperseded = assertThrows(InvalidAttemptTransitionException::class.java) {
            service.requestCancel(scope, NAMESPACE_ID, WORKFLOW_ID, STEP_ID, "attempt-superseded")
        }
        assertThat(cancelSuperseded.errorCode).isEqualTo("ATTEMPT_INVALID_TRANSITION")
    }

    @Test
    fun `a retry registers a brand new attempt N+1 and never reactivates the sealed attempt N`() {
        service.register(scope, attempt("attempt-n").copy(attemptNumber = 1))
        claimToRunning("attempt-n", "owner-a")
        val failed = service.finalize(
            scope,
            namespaceId = NAMESPACE_ID,
            workflowId = WORKFLOW_ID,
            stepId = STEP_ID,
            attemptId = "attempt-n",
            ownerToken = "owner-a",
            target = AgentAttemptStatus.FAILED,
            failureCode = "AGENT_CASE_ERROR",
        )

        // Reusing the sealed attempt id for a retry is an explicit collision,
        // never a silent reactivation.
        val collision = assertThrows(IdempotencyKeyCollisionException::class.java) {
            service.registerRetry(scope, attempt("attempt-n").copy(attemptNumber = 2))
        }
        assertThat(collision.errorCode).isEqualTo("IDEMPOTENCY_KEY_COLLISION")

        // The retry is a brand-new attempt id carrying the next attempt number.
        val retry = service.registerRetry(scope, attempt("attempt-n-plus-1").copy(attemptNumber = 2))
        assertThat(retry.attemptNumber).isEqualTo(2)
        assertThat(retry.status).isEqualTo(AgentAttemptStatus.PENDING)

        // Attempt N is untouched by the registration of its successor.
        val persisted = service.find(scope, NAMESPACE_ID, WORKFLOW_ID, STEP_ID, "attempt-n")
        assertThat(persisted!!.status).isEqualTo(AgentAttemptStatus.FAILED)
        assertThat(persisted.attemptNumber).isEqualTo(1)
        assertThat(persisted.revision).isEqualTo(failed.revision)
        assertThat(persisted.completedAt).isEqualTo(failed.completedAt)
        assertThat(persisted.failureCode).isEqualTo("AGENT_CASE_ERROR")
    }

    private fun claimToRunning(attemptId: String, ownerToken: String) {
        service.claim(
            scope,
            namespaceId = NAMESPACE_ID,
            workflowId = WORKFLOW_ID,
            stepId = STEP_ID,
            attemptId = attemptId,
            ownerToken = ownerToken,
            leaseTtlMs = 60_000,
        )
        service.transition(
            scope,
            namespaceId = NAMESPACE_ID,
            workflowId = WORKFLOW_ID,
            stepId = STEP_ID,
            attemptId = attemptId,
            ownerToken = ownerToken,
            target = AgentAttemptStatus.STARTING,
        )
        service.transition(
            scope,
            namespaceId = NAMESPACE_ID,
            workflowId = WORKFLOW_ID,
            stepId = STEP_ID,
            attemptId = attemptId,
            ownerToken = ownerToken,
            target = AgentAttemptStatus.RUNNING,
        )
    }

    private fun attempt(attemptId: String): DurableAgentAttempt = DurableAgentAttempt(
        attemptId = attemptId,
        caseId = "case-1",
        namespaceId = NAMESPACE_ID,
        workflowId = WORKFLOW_ID,
        stepId = STEP_ID,
        attemptNumber = 1,
        agentName = "builder",
        capabilityToken = "cap-token",
        turnCorrelation = "turn-1",
        commandId = "cmd-1",
    )

    companion object {
        private const val NAMESPACE_ID = "ns-sealing"
        private const val WORKFLOW_ID = "wf-sealing"
        private const val STEP_ID = "step-sealing"
    }
}
