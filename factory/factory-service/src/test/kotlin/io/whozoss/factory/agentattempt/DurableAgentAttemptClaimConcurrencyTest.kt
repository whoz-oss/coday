package io.whozoss.factory.agentattempt

import io.whozoss.factory.Neo4jDomainIntegrationTest
import io.whozoss.factory.agentattempt.domain.AgentAttemptStatus
import io.whozoss.factory.agentattempt.domain.AttemptClaimConflictException
import io.whozoss.factory.agentattempt.domain.DurableAgentAttempt
import io.whozoss.factory.agentattempt.service.DurableAgentAttemptService
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test
import org.springframework.beans.factory.annotation.Autowired
import java.util.concurrent.Callable
import java.util.concurrent.CountDownLatch
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit

/**
 * Real-concurrency integration test of the atomic durable-attempt claim.
 *
 * Many threads claim the same attempt simultaneously, each with a distinct
 * `ownerToken`. The claim is serialised (process-local lock + `REQUIRES_NEW`
 * transaction) and applied as an atomic Cypher compare-and-set, so exactly one
 * execution owns the attempt and every loser is rejected with
 * `ATTEMPT_CLAIM_CONFLICT`.
 */
class DurableAgentAttemptClaimConcurrencyTest : Neo4jDomainIntegrationTest() {

    @Autowired
    private lateinit var service: DurableAgentAttemptService

    @Test
    fun `concurrent claims on the same attempt yield exactly one owner`() {
        service.register(scope, attempt("attempt-race"))

        val contenders = 8
        val pool = Executors.newFixedThreadPool(contenders)
        val startGate = CountDownLatch(1)
        val futures = (0 until contenders).map { index ->
            pool.submit(
                Callable {
                    startGate.await()
                    try {
                        ClaimOutcome.Claimed(
                            service.claim(
                                scope,
                                namespaceId = NAMESPACE_ID,
                                workflowId = WORKFLOW_ID,
                                stepId = STEP_ID,
                                attemptId = "attempt-race",
                                ownerToken = "owner-$index",
                                leaseTtlMs = 60_000,
                            ),
                        )
                    } catch (conflict: AttemptClaimConflictException) {
                        ClaimOutcome.Conflicted(conflict)
                    }
                },
            )
        }
        startGate.countDown()
        val outcomes = futures.map { it.get(30, TimeUnit.SECONDS) }
        pool.shutdown()

        val winners = outcomes.filterIsInstance<ClaimOutcome.Claimed>()
        val losers = outcomes.filterIsInstance<ClaimOutcome.Conflicted>()

        assertThat(winners).hasSize(1)
        assertThat(losers).hasSize(contenders - 1)
        losers.forEach { loser ->
            assertThat(loser.failure.errorCode).isEqualTo("ATTEMPT_CLAIM_CONFLICT")
        }

        val winner = winners[0].attempt
        assertThat(winner.status).isEqualTo(AgentAttemptStatus.CLAIMING)

        val persisted = service.find(scope, NAMESPACE_ID, WORKFLOW_ID, STEP_ID, "attempt-race")
        assertThat(persisted).isNotNull
        assertThat(persisted!!.status).isEqualTo(AgentAttemptStatus.CLAIMING)
        assertThat(persisted.ownerToken).isEqualTo(winner.ownerToken)
        assertThat(persisted.startedAt).isNotNull
    }

    @Test
    fun `a repeated claim by the same owner is idempotent`() {
        service.register(scope, attempt("attempt-same-owner"))
        val first = service.claim(
            scope,
            namespaceId = NAMESPACE_ID,
            workflowId = WORKFLOW_ID,
            stepId = STEP_ID,
            attemptId = "attempt-same-owner",
            ownerToken = "owner-a",
            leaseTtlMs = 60_000,
        )

        val second = service.claim(
            scope,
            namespaceId = NAMESPACE_ID,
            workflowId = WORKFLOW_ID,
            stepId = STEP_ID,
            attemptId = "attempt-same-owner",
            ownerToken = "owner-a",
            leaseTtlMs = 60_000,
        )

        assertThat(second.status).isEqualTo(AgentAttemptStatus.CLAIMING)
        assertThat(second.ownerToken).isEqualTo("owner-a")
        assertThat(second.startedAt).isEqualTo(first.startedAt)
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

    private sealed interface ClaimOutcome {
        data class Claimed(val attempt: DurableAgentAttempt) : ClaimOutcome
        data class Conflicted(val failure: AttemptClaimConflictException) : ClaimOutcome
    }

    companion object {
        private const val NAMESPACE_ID = "ns-1"
        private const val WORKFLOW_ID = "wf-1"
        private const val STEP_ID = "step-1"
    }
}
