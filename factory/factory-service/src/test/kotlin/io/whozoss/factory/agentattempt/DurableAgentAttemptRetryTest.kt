package io.whozoss.factory.agentattempt

import io.whozoss.factory.Neo4jDomainIntegrationTest
import io.whozoss.factory.agentattempt.domain.AgentAttemptStatus
import io.whozoss.factory.agentattempt.domain.AttemptClaimConflictException
import io.whozoss.factory.agentattempt.domain.AttemptLeaseFencingException
import io.whozoss.factory.agentattempt.domain.DurableAgentAttempt
import io.whozoss.factory.agentattempt.domain.IdempotencyKeyCollisionException
import io.whozoss.factory.agentattempt.service.DurableAgentAttemptService
import io.whozoss.factory.capability.CapabilityExecutionService
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Assertions.assertThrows
import org.junit.jupiter.api.Test
import org.springframework.beans.factory.annotation.Autowired

/**
 * Req 7 attestation: a retry registers a BRAND-NEW attempt with an incremented
 * `attemptNumber`; a terminal attempt is immutable and is never reactivated.
 *
 * The caller hook (allocating the retry attempt id via
 * [CapabilityExecutionService.retryAttemptId] with
 * [DurableAgentAttemptService.nextAttemptNumber] when a blocked step is
 * re-run) is proven here at the [DurableAgentAttemptService] level; wiring it
 * into the workflow retry route is deliberately deferred.
 */
class DurableAgentAttemptRetryTest : Neo4jDomainIntegrationTest() {

    @Autowired
    private lateinit var service: DurableAgentAttemptService

    @Test
    fun `a terminal attempt is immutable and a retry registers a new attempt with the next number`() {
        // Attempt #1 runs and fails terminally.
        service.register(scope, attempt(ATTEMPT_ID_1, attemptNumber = 1))
        claimToRunning(ATTEMPT_ID_1, "owner-a")
        service.finalize(
            scope,
            namespaceId = NAMESPACE_ID,
            workflowId = WORKFLOW_ID,
            stepId = STEP_ID,
            attemptId = ATTEMPT_ID_1,
            ownerToken = "owner-a",
            target = AgentAttemptStatus.FAILED,
            failureCode = "AGENT_CASE_ERROR",
        )
        val journalBefore = service.journal(scope, NAMESPACE_ID, WORKFLOW_ID, STEP_ID, ATTEMPT_ID_1)

        // Immutability: the terminal attempt rejects any reactivation, however tried.
        val claimFailure = assertThrows(AttemptClaimConflictException::class.java) {
            service.claim(
                scope,
                namespaceId = NAMESPACE_ID,
                workflowId = WORKFLOW_ID,
                stepId = STEP_ID,
                attemptId = ATTEMPT_ID_1,
                ownerToken = "owner-b",
                leaseTtlMs = 60_000,
            )
        }
        assertThat(claimFailure.errorCode).isEqualTo("ATTEMPT_CLAIM_CONFLICT")
        val finalizeFailure = assertThrows(AttemptLeaseFencingException::class.java) {
            service.finalize(
                scope,
                namespaceId = NAMESPACE_ID,
                workflowId = WORKFLOW_ID,
                stepId = STEP_ID,
                attemptId = ATTEMPT_ID_1,
                ownerToken = "owner-b",
                target = AgentAttemptStatus.SUCCEEDED,
            )
        }
        assertThat(finalizeFailure.errorCode).isEqualTo("ATTEMPT_LEASE_FENCED")

        // The retry: attemptNumber = MAX+1, a brand-new attempt id, a distinct node.
        val nextNumber = service.nextAttemptNumber(scope, NAMESPACE_ID, WORKFLOW_ID, STEP_ID)
        assertThat(nextNumber).isEqualTo(2)
        val retryId = CapabilityExecutionService.retryAttemptId(WORKFLOW_ID, STEP_ID, nextNumber)
        assertThat(retryId).isNotEqualTo(ATTEMPT_ID_1)

        val retry = service.registerRetry(scope, attempt(retryId, attemptNumber = nextNumber))

        assertThat(retry.attemptNumber).isEqualTo(2)
        assertThat(retry.status).isEqualTo(AgentAttemptStatus.PENDING)
        assertThat(retry.revision).isEqualTo(1)

        // Attempt #1 is untouched: still failed, same revision, same journal.
        val first = service.find(scope, NAMESPACE_ID, WORKFLOW_ID, STEP_ID, ATTEMPT_ID_1)
        assertThat(first!!.status).isEqualTo(AgentAttemptStatus.FAILED)
        assertThat(first.attemptNumber).isEqualTo(1)
        assertThat(service.journal(scope, NAMESPACE_ID, WORKFLOW_ID, STEP_ID, ATTEMPT_ID_1)).isEqualTo(journalBefore)

        // Both attempts coexist under the same workflow.
        val attempts = service.findByWorkflow(scope, NAMESPACE_ID, WORKFLOW_ID)
        assertThat(attempts.map { it.attemptId }).containsExactlyInAnyOrder(ATTEMPT_ID_1, retryId)

        // The retry attempt runs its own independent lifecycle to success.
        claimToRunning(retryId, "owner-c")
        val succeeded = service.finalize(
            scope,
            namespaceId = NAMESPACE_ID,
            workflowId = WORKFLOW_ID,
            stepId = STEP_ID,
            attemptId = retryId,
            ownerToken = "owner-c",
            target = AgentAttemptStatus.SUCCEEDED,
            resultEvidenceId = "evidence-retry",
        )
        assertThat(succeeded.status).isEqualTo(AgentAttemptStatus.SUCCEEDED)
        assertThat(service.find(scope, NAMESPACE_ID, WORKFLOW_ID, STEP_ID, ATTEMPT_ID_1)!!.status)
            .isEqualTo(AgentAttemptStatus.FAILED)
    }

    @Test
    fun `the first attempt number is one and a retry with a stale number or an existing id is rejected`() {
        assertThat(service.nextAttemptNumber(scope, NAMESPACE_ID, WORKFLOW_ID, STEP_ID)).isEqualTo(1)

        // A retry whose number is not MAX+1 is a defensive rejection.
        assertThrows(IllegalArgumentException::class.java) {
            service.registerRetry(
                scope,
                attempt(CapabilityExecutionService.retryAttemptId(WORKFLOW_ID, STEP_ID, 7), attemptNumber = 7),
            )
        }

        // A retry reusing an existing attempt id is an explicit collision, never
        // a silent reactivation of the prior attempt.
        service.register(scope, attempt(ATTEMPT_ID_1, attemptNumber = 1))
        val collision = assertThrows(IdempotencyKeyCollisionException::class.java) {
            service.registerRetry(scope, attempt(ATTEMPT_ID_1, attemptNumber = 2))
        }
        assertThat(collision.errorCode).isEqualTo("IDEMPOTENCY_KEY_COLLISION")
        assertThat(service.find(scope, NAMESPACE_ID, WORKFLOW_ID, STEP_ID, ATTEMPT_ID_1)!!.attemptNumber).isEqualTo(1)
    }

    @Test
    fun `latest step attempt selects the question successor rather than stable attempt one`() {
        service.register(scope, attempt(ATTEMPT_ID_1, attemptNumber = 1))
        claimToRunning(ATTEMPT_ID_1, "owner-a")
        service.transition(
            scope, NAMESPACE_ID, WORKFLOW_ID, STEP_ID, ATTEMPT_ID_1, "owner-a",
            AgentAttemptStatus.WAITING_HUMAN,
        )
        service.supersede(scope, NAMESPACE_ID, WORKFLOW_ID, STEP_ID, ATTEMPT_ID_1)

        val successorId = CapabilityExecutionService.retryAttemptId(WORKFLOW_ID, STEP_ID, 2)
        service.registerRetry(scope, attempt(successorId, attemptNumber = 2))

        assertThat(service.findLatestForStep(scope, NAMESPACE_ID, WORKFLOW_ID, STEP_ID)?.attemptId)
            .isEqualTo(successorId)
    }

    @Test
    fun `attempt one keeps the stable attempt id form`() {
        assertThat(CapabilityExecutionService.retryAttemptId(WORKFLOW_ID, STEP_ID, 1))
            .isEqualTo(CapabilityExecutionService.stableAttemptId(WORKFLOW_ID, STEP_ID))
        assertThat(CapabilityExecutionService.retryAttemptId(WORKFLOW_ID, STEP_ID, 2))
            .isNotEqualTo(CapabilityExecutionService.stableAttemptId(WORKFLOW_ID, STEP_ID))
        assertThat(CapabilityExecutionService.retryAttemptId(WORKFLOW_ID, STEP_ID, 2))
            .matches("^[A-Za-z0-9][A-Za-z0-9._-]{0,127}$")
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

    private fun attempt(attemptId: String, attemptNumber: Int): DurableAgentAttempt = DurableAgentAttempt(
        attemptId = attemptId,
        caseId = "case-1",
        namespaceId = NAMESPACE_ID,
        workflowId = WORKFLOW_ID,
        stepId = STEP_ID,
        attemptNumber = attemptNumber,
        agentName = "builder",
    )

    companion object {
        private const val NAMESPACE_ID = "ns-retry"
        private const val WORKFLOW_ID = "wf-retry"
        private const val STEP_ID = "step-retry"
        private val ATTEMPT_ID_1 = CapabilityExecutionService.stableAttemptId(WORKFLOW_ID, STEP_ID)
    }
}
