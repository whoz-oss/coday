package io.whozoss.factory.agentattempt

import io.whozoss.factory.Neo4jDomainIntegrationTest
import io.whozoss.factory.agentattempt.domain.AgentAttemptStatus
import io.whozoss.factory.agentattempt.domain.AttemptLeaseFencingException
import io.whozoss.factory.agentattempt.domain.DurableAgentAttempt
import io.whozoss.factory.agentattempt.domain.InvalidAttemptTransitionException
import io.whozoss.factory.agentattempt.service.DurableAgentAttemptService
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Assertions.assertThrows
import org.junit.jupiter.api.Test
import org.neo4j.driver.Values
import org.springframework.beans.factory.annotation.Autowired

/**
 * Fencing, state-machine and idempotence integration tests of the durable
 * execution attempt aggregate.
 *
 * A worker whose `ownerToken`/`leaseToken` diverged (lost, expired or preempted
 * lease) is fenced out of finalization with `ATTEMPT_LEASE_FENCED`; only the
 * transitions allowed by the state machine land, so an incomplete / timed-out /
 * unknown attempt can never finalize as `succeeded`; and a re-registration with
 * the same `attemptId` never creates a duplicate node.
 */
class DurableAgentAttemptFencingTest : Neo4jDomainIntegrationTest() {

    @Autowired
    private lateinit var service: DurableAgentAttemptService

    @Test
    fun `finalize with a divergent lease token is fenced and leaves the attempt unchanged`() {
        service.register(scope, attempt("attempt-fence"))
        claimToRunning("attempt-fence", "owner-a")

        val failure = assertThrows(AttemptLeaseFencingException::class.java) {
            service.finalize(
                scope,
                namespaceId = NAMESPACE_ID,
                workflowId = WORKFLOW_ID,
                stepId = STEP_ID,
                attemptId = "attempt-fence",
                ownerToken = "owner-b",
                target = AgentAttemptStatus.SUCCEEDED,
            )
        }

        assertThat(failure.errorCode).isEqualTo("ATTEMPT_LEASE_FENCED")
        val persisted = service.find(scope, NAMESPACE_ID, WORKFLOW_ID, STEP_ID, "attempt-fence")
        assertThat(persisted!!.status).isEqualTo(AgentAttemptStatus.RUNNING)
        assertThat(persisted.ownerToken).isEqualTo("owner-a")
        assertThat(persisted.completedAt).isNull()
    }

    @Test
    fun `a preempted worker whose lease expired is fenced out of finalization`() {
        service.register(scope, attempt("attempt-preempt"))
        service.claim(
            scope,
            namespaceId = NAMESPACE_ID,
            workflowId = WORKFLOW_ID,
            stepId = STEP_ID,
            attemptId = "attempt-preempt",
            ownerToken = "owner-a",
            leaseTtlMs = 1,
        )

        Thread.sleep(25)

        // The lease expired: a new worker preempts the attempt (owner rotation).
        val reclaimed = service.claim(
            scope,
            namespaceId = NAMESPACE_ID,
            workflowId = WORKFLOW_ID,
            stepId = STEP_ID,
            attemptId = "attempt-preempt",
            ownerToken = "owner-b",
            leaseTtlMs = 60_000,
        )
        assertThat(reclaimed.ownerToken).isEqualTo("owner-b")

        val failure = assertThrows(AttemptLeaseFencingException::class.java) {
            service.finalize(
                scope,
                namespaceId = NAMESPACE_ID,
                workflowId = WORKFLOW_ID,
                stepId = STEP_ID,
                attemptId = "attempt-preempt",
                ownerToken = "owner-a",
                target = AgentAttemptStatus.SUCCEEDED,
            )
        }
        assertThat(failure.errorCode).isEqualTo("ATTEMPT_LEASE_FENCED")
    }

    @Test
    fun `the owner finalizes a running attempt as succeeded and the replay is idempotent`() {
        service.register(scope, attempt("attempt-success"))
        claimToRunning("attempt-success", "owner-a")

        val finalized = service.finalize(
            scope,
            namespaceId = NAMESPACE_ID,
            workflowId = WORKFLOW_ID,
            stepId = STEP_ID,
            attemptId = "attempt-success",
            ownerToken = "owner-a",
            target = AgentAttemptStatus.SUCCEEDED,
            resultEvidenceId = "evidence-1",
            lastObservedEventId = "evt-42",
        )

        assertThat(finalized.status).isEqualTo(AgentAttemptStatus.SUCCEEDED)
        assertThat(finalized.completedAt).isNotNull
        assertThat(finalized.resultEvidenceId).isEqualTo("evidence-1")
        assertThat(finalized.lastObservedEventId).isEqualTo("evt-42")

        // Replaying the same finalize with the same owner and target is idempotent.
        val replayed = service.finalize(
            scope,
            namespaceId = NAMESPACE_ID,
            workflowId = WORKFLOW_ID,
            stepId = STEP_ID,
            attemptId = "attempt-success",
            ownerToken = "owner-a",
            target = AgentAttemptStatus.SUCCEEDED,
            resultEvidenceId = "evidence-1",
        )
        assertThat(replayed.status).isEqualTo(AgentAttemptStatus.SUCCEEDED)
        assertThat(replayed.completedAt).isEqualTo(finalized.completedAt)
    }

    @Test
    fun `an incomplete or never-run attempt can never finalize as succeeded`() {
        service.register(scope, attempt("attempt-pending"))

        val pendingFailure = assertThrows(InvalidAttemptTransitionException::class.java) {
            service.finalize(
                scope,
                namespaceId = NAMESPACE_ID,
                workflowId = WORKFLOW_ID,
                stepId = STEP_ID,
                attemptId = "attempt-pending",
                ownerToken = "owner-a",
                target = AgentAttemptStatus.SUCCEEDED,
            )
        }
        assertThat(pendingFailure.errorCode).isEqualTo("ATTEMPT_INVALID_TRANSITION")

        service.claim(
            scope,
            namespaceId = NAMESPACE_ID,
            workflowId = WORKFLOW_ID,
            stepId = STEP_ID,
            attemptId = "attempt-pending",
            ownerToken = "owner-a",
            leaseTtlMs = 60_000,
        )
        service.transition(
            scope,
            namespaceId = NAMESPACE_ID,
            workflowId = WORKFLOW_ID,
            stepId = STEP_ID,
            attemptId = "attempt-pending",
            ownerToken = "owner-a",
            target = AgentAttemptStatus.STARTING,
        )

        val startingFailure = assertThrows(InvalidAttemptTransitionException::class.java) {
            service.finalize(
                scope,
                namespaceId = NAMESPACE_ID,
                workflowId = WORKFLOW_ID,
                stepId = STEP_ID,
                attemptId = "attempt-pending",
                ownerToken = "owner-a",
                target = AgentAttemptStatus.SUCCEEDED,
            )
        }
        assertThat(startingFailure.errorCode).isEqualTo("ATTEMPT_INVALID_TRANSITION")

        // The timeout / unknown-outcome path finalizes as indeterminate, never succeeded.
        val indeterminate = service.finalize(
            scope,
            namespaceId = NAMESPACE_ID,
            workflowId = WORKFLOW_ID,
            stepId = STEP_ID,
            attemptId = "attempt-pending",
            ownerToken = "owner-a",
            target = AgentAttemptStatus.INDETERMINATE,
            failureCode = "timeout",
        )
        assertThat(indeterminate.status).isEqualTo(AgentAttemptStatus.INDETERMINATE)
        assertThat(indeterminate.failureCode).isEqualTo("timeout")
    }

    /**
     * Req 6 attestation: the observation-timeout path of a RUNNING attempt
     * finalizes as `indeterminate` (never `succeeded`), and the terminal
     * `indeterminate` record can never be flipped to `succeeded` afterwards.
     */
    @Test
    fun `a running attempt that times out finalizes as indeterminate and can never flip to succeeded`() {
        service.register(scope, attempt("attempt-timeout"))
        claimToRunning("attempt-timeout", "owner-a")

        val indeterminate = service.finalize(
            scope,
            namespaceId = NAMESPACE_ID,
            workflowId = WORKFLOW_ID,
            stepId = STEP_ID,
            attemptId = "attempt-timeout",
            ownerToken = "owner-a",
            target = AgentAttemptStatus.INDETERMINATE,
            failureCode = "timeout",
        )
        assertThat(indeterminate.status).isEqualTo(AgentAttemptStatus.INDETERMINATE)
        assertThat(indeterminate.failureCode).isEqualTo("timeout")
        assertThat(indeterminate.completedAt).isNotNull

        val failure = assertThrows(InvalidAttemptTransitionException::class.java) {
            service.finalize(
                scope,
                namespaceId = NAMESPACE_ID,
                workflowId = WORKFLOW_ID,
                stepId = STEP_ID,
                attemptId = "attempt-timeout",
                ownerToken = "owner-a",
                target = AgentAttemptStatus.SUCCEEDED,
            )
        }
        assertThat(failure.errorCode).isEqualTo("ATTEMPT_INVALID_TRANSITION")
        val persisted = service.find(scope, NAMESPACE_ID, WORKFLOW_ID, STEP_ID, "attempt-timeout")
        assertThat(persisted!!.status).isEqualTo(AgentAttemptStatus.INDETERMINATE)
        assertThat(persisted.failureCode).isEqualTo("timeout")
    }

    @Test
    fun `re-registering the same attemptId creates no duplicate and preserves the live state`() {
        service.register(scope, attempt("attempt-idem"))
        claimToRunning("attempt-idem", "owner-a")

        val replayed = service.register(scope, attempt("attempt-idem"))

        assertThat(countNodes("attempt-idem")).isEqualTo(1)
        assertThat(replayed.status).isEqualTo(AgentAttemptStatus.RUNNING)
        assertThat(replayed.ownerToken).isEqualTo("owner-a")
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

    private fun countNodes(attemptId: String): Long =
        neo4jDriver.session().use { session ->
            session.executeRead { tx ->
                tx.run(
                    "MATCH (a:DurableAgentAttempt {attemptId: ${'$'}attemptId}) RETURN count(a) AS total",
                    Values.parameters("attemptId", attemptId),
                ).single()["total"].asLong()
            }
        }

    companion object {
        private const val NAMESPACE_ID = "ns-1"
        private const val WORKFLOW_ID = "wf-1"
        private const val STEP_ID = "step-1"
    }
}
