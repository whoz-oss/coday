package io.whozoss.factory.agentattempt

import io.whozoss.factory.Neo4jDomainIntegrationTest
import io.whozoss.factory.adapter.agentos.AgentOsExecutionAdapter
import io.whozoss.factory.adapter.agentos.AgentOsExecutionVerdict
import io.whozoss.factory.adapter.agentos.CaseHandle
import io.whozoss.factory.agentattempt.domain.AgentAttemptStatus
import io.whozoss.factory.agentattempt.domain.DurableAgentAttempt
import io.whozoss.factory.agentattempt.service.BridgeCancellationService
import io.whozoss.factory.agentattempt.service.DurableAgentAttemptService
import io.whozoss.factory.error.RevisionConflictException
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicInteger
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Assertions.assertThrows
import org.junit.jupiter.api.Test
import org.springframework.beans.factory.annotation.Autowired

/**
 * Explicit business cancellation tests (Lot H, step 8).
 *
 * Cancellation is a first-class command, not a side effect of observation:
 * closing the SSE stream does NOT cancel the run. Only `requestCancel` issues the
 * interrupt/kill, reconciles the post-kill state and persists the durable
 * `INTERRUPTED` status under revision fencing.
 */
class BridgeCancellationServiceTest : Neo4jDomainIntegrationTest() {

    @Autowired
    private lateinit var attempts: DurableAgentAttemptService

    private val namespace = "3c7f9e3f-9e2b-4f1a-8c7d-cancel-bridge"

    private class FakeAdapter : AgentOsExecutionAdapter {
        val interruptCalls = AtomicInteger()
        val killCalls = AtomicInteger()
        val reconcileCalls = AtomicInteger()
        val observeCalls = AtomicInteger()
        private val interrupted = AtomicBoolean(false)

        override fun createOrRecoverExecution(
            namespaceId: String,
            workflowId: String,
            stepId: String,
            externalUserId: String?,
            attemptId: String,
            capabilityToken: String?,
            caseId: String,
        ): CaseHandle = CaseHandle(caseId, namespaceId, false)

        override fun startTurn(
            caseId: String,
            persona: String,
            brief: String,
            externalUserId: String?,
            attemptId: String,
            capabilityToken: String?,
        ) = Unit

        override fun observeTurn(caseId: String, attemptId: String, timeoutMs: Long): AgentOsExecutionVerdict {
            observeCalls.incrementAndGet()
            return AgentOsExecutionVerdict.Indeterminate("still running")
        }

        override fun reconcile(caseId: String): AgentOsExecutionVerdict {
            reconcileCalls.incrementAndGet()
            return if (interrupted.get()) {
                AgentOsExecutionVerdict.Interrupted("User requested cancellation")
            } else {
                AgentOsExecutionVerdict.Indeterminate("still running")
            }
        }

        override fun interrupt(caseId: String, reason: String) {
            interruptCalls.incrementAndGet()
            interrupted.set(true)
        }

        override fun kill(caseId: String) {
            killCalls.incrementAndGet()
            interrupted.set(true)
        }
    }

    private fun attempt(attemptId: String): DurableAgentAttempt = DurableAgentAttempt(
        attemptId = attemptId,
        caseId = "case:$attemptId",
        namespaceId = namespace,
        workflowId = "wf-cancel",
        stepId = "step-1",
        attemptNumber = 1,
        agentName = "architect",
        brief = "do the thing",
    )

    private fun claimToRunning(attemptId: String, ownerToken: String = "live-worker", leaseTtlMs: Long = 60_000) {
        attempts.claim(scope, namespace, "wf-cancel", "step-1", attemptId, ownerToken, leaseTtlMs = leaseTtlMs)
        attempts.transition(scope, namespace, "wf-cancel", "step-1", attemptId, ownerToken, AgentAttemptStatus.STARTING)
        attempts.transition(scope, namespace, "wf-cancel", "step-1", attemptId, ownerToken, AgentAttemptStatus.RUNNING)
    }

    private fun status(attemptId: String) = attempts.find(scope, namespace, "wf-cancel", "step-1", attemptId)!!.status

    @Test
    fun `requestCancel interrupts the case and persists INTERRUPTED under revision fencing`() {
        attempts.register(scope, attempt("attempt-cancel"))
        claimToRunning("attempt-cancel")
        val revision = attempts.find(scope, namespace, "wf-cancel", "step-1", "attempt-cancel")!!.revision
        val adapter = FakeAdapter()

        val outcome = BridgeCancellationService(attempts, adapter)
            .requestCancel(scope, namespace, "wf-cancel", "attempt-cancel", revision)

        assertThat(outcome.status).isEqualTo(AgentAttemptStatus.INTERRUPTED)
        assertThat(outcome.idempotent).isFalse()
        assertThat(outcome.reconciledVerdict).isEqualTo("Interrupted")
        assertThat(adapter.interruptCalls.get()).isEqualTo(1)
        assertThat(adapter.reconcileCalls.get()).isEqualTo(1)
        assertThat(status("attempt-cancel")).isEqualTo(AgentAttemptStatus.INTERRUPTED)
    }

    @Test
    fun `a stale revision is rejected as a conflict`() {
        attempts.register(scope, attempt("attempt-stale"))
        claimToRunning("attempt-stale")
        val revision = attempts.find(scope, namespace, "wf-cancel", "step-1", "attempt-stale")!!.revision
        val adapter = FakeAdapter()

        assertThrows(RevisionConflictException::class.java) {
            BridgeCancellationService(attempts, adapter)
                .requestCancel(scope, namespace, "wf-cancel", "attempt-stale", revision - 1)
        }
        assertThat(status("attempt-stale")).isEqualTo(AgentAttemptStatus.RUNNING)
    }

    @Test
    fun `cancelling an already interrupted attempt is idempotent`() {
        attempts.register(scope, attempt("attempt-idem"))
        claimToRunning("attempt-idem")
        val revision = attempts.find(scope, namespace, "wf-cancel", "step-1", "attempt-idem")!!.revision
        val adapter = FakeAdapter()
        val service = BridgeCancellationService(attempts, adapter)
        service.requestCancel(scope, namespace, "wf-cancel", "attempt-idem", revision)

        val replay = service.requestCancel(scope, namespace, "wf-cancel", "attempt-idem", revision + 1)

        assertThat(replay.idempotent).isTrue()
        assertThat(replay.status).isEqualTo(AgentAttemptStatus.INTERRUPTED)
        // The interrupt was issued only once.
        assertThat(adapter.interruptCalls.get()).isEqualTo(1)
    }

    @Test
    fun `closing the SSE stream alone does not cancel the run`() {
        attempts.register(scope, attempt("attempt-observe"))
        claimToRunning("attempt-observe")
        val adapter = FakeAdapter()

        // Simulate a client that merely observes then disconnects: no command is
        // sent, so the attempt stays running and no interrupt/kill is issued.
        adapter.observeTurn("case:attempt-observe", "attempt-observe", 1_000)

        assertThat(status("attempt-observe")).isEqualTo(AgentAttemptStatus.RUNNING)
        assertThat(adapter.interruptCalls.get()).isZero()
        assertThat(adapter.killCalls.get()).isZero()
    }
}
