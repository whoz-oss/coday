package io.whozoss.factory.agentattempt

import io.whozoss.factory.Neo4jDomainIntegrationTest
import io.whozoss.factory.adapter.agentos.AgentOsExecutionAdapter
import io.whozoss.factory.adapter.agentos.AgentOsExecutionVerdict
import io.whozoss.factory.adapter.agentos.CaseHandle
import io.whozoss.factory.agentattempt.domain.AgentAttemptStatus
import io.whozoss.factory.agentattempt.domain.AttemptLeaseFencingException
import io.whozoss.factory.agentattempt.domain.DurableAgentAttempt
import io.whozoss.factory.agentattempt.service.BridgeRecoveryWorker
import io.whozoss.factory.agentattempt.service.DurableAgentAttemptService
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.atomic.AtomicInteger
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Assertions.assertThrows
import org.junit.jupiter.api.Test
import org.springframework.beans.factory.annotation.Autowired

/**
 * Startup recovery tests (Lot H, step 8) of the durable AgentOS-Factory bridge.
 *
 * The worker runs against the real Neo4j-backed [DurableAgentAttemptService] and
 * a stateful fake [AgentOsExecutionAdapter], so the claim/fencing/state-machine
 * semantics exercised are exactly the production ones.
 */
class BridgeRecoveryWorkerTest : Neo4jDomainIntegrationTest() {

    @Autowired
    private lateinit var attempts: DurableAgentAttemptService

    private val namespace = "2b6f8d2f-8d1a-4f0e-9b6e-recovery"

    private data class StartTurn(val caseId: String, val brief: String, val attemptId: String)

    private class FakeAdapter(
        private val reconcileVerdict: (String) -> AgentOsExecutionVerdict,
        private val observeVerdict: (String) -> AgentOsExecutionVerdict,
    ) : AgentOsExecutionAdapter {
        val startTurns = CopyOnWriteArrayList<StartTurn>()
        val reconcileCalls = AtomicInteger()
        val observeCalls = AtomicInteger()
        val createCalls = AtomicInteger()

        override fun createOrRecoverExecution(
            namespaceId: String,
            workflowId: String,
            stepId: String,
            externalUserId: String?,
            attemptId: String,
            capabilityToken: String?,
            caseId: String,
        ): CaseHandle {
            createCalls.incrementAndGet()
            return CaseHandle(caseId, namespaceId, false)
        }

        override fun startTurn(
            caseId: String,
            persona: String,
            brief: String,
            externalUserId: String?,
            attemptId: String,
            capabilityToken: String?,
        ) {
            startTurns.add(StartTurn(caseId, brief, attemptId))
        }

        override fun observeTurn(caseId: String, attemptId: String, timeoutMs: Long): AgentOsExecutionVerdict {
            observeCalls.incrementAndGet()
            return observeVerdict(caseId)
        }

        override fun reconcile(caseId: String): AgentOsExecutionVerdict {
            reconcileCalls.incrementAndGet()
            return reconcileVerdict(caseId)
        }

        override fun interrupt(caseId: String, reason: String) = Unit

        override fun kill(caseId: String) = Unit
    }

    private fun attempt(attemptId: String, brief: String? = "do the thing"): DurableAgentAttempt = DurableAgentAttempt(
        attemptId = attemptId,
        caseId = "case:$attemptId",
        namespaceId = namespace,
        workflowId = "wf-recovery",
        stepId = "step-1",
        attemptNumber = 1,
        agentName = "architect",
        brief = brief,
    )

    private fun claimToRunning(attemptId: String, ownerToken: String, leaseTtlMs: Long) {
        attempts.claim(scope, namespace, "wf-recovery", "step-1", attemptId, ownerToken, leaseTtlMs = leaseTtlMs)
        attempts.transition(scope, namespace, "wf-recovery", "step-1", attemptId, ownerToken, AgentAttemptStatus.STARTING)
        attempts.transition(scope, namespace, "wf-recovery", "step-1", attemptId, ownerToken, AgentAttemptStatus.RUNNING)
    }

    private fun status(attemptId: String) = attempts.find(scope, namespace, "wf-recovery", "step-1", attemptId)!!.status

    @Test
    fun `restart during a turn resumes observation without a second turn`() {
        attempts.register(scope, attempt("attempt-resume"))
        // The prior worker's lease expired: recovery may preempt it.
        claimToRunning("attempt-resume", "crashed-worker", leaseTtlMs = 0)
        val adapter = FakeAdapter(
            reconcileVerdict = { AgentOsExecutionVerdict.Indeterminate("still running") },
            observeVerdict = { AgentOsExecutionVerdict.Succeeded(mapOf("summary" to "resumed")) },
        )

        val report = BridgeRecoveryWorker(attempts, adapter).recover()

        assertThat(report.resumed).isEqualTo(1)
        assertThat(status("attempt-resume")).isEqualTo(AgentAttemptStatus.SUCCEEDED)
        // No duplicate work: the turn was already accepted, only observation resumed.
        assertThat(adapter.startTurns).isEmpty()
        assertThat(adapter.observeCalls.get()).isEqualTo(1)
    }

    @Test
    fun `crash after the AgentOS result but before the Factory commit finalizes on one reconciliation`() {
        attempts.register(scope, attempt("attempt-result"))
        claimToRunning("attempt-result", "crashed-worker", leaseTtlMs = 0)
        val adapter = FakeAdapter(
            reconcileVerdict = { AgentOsExecutionVerdict.Succeeded(mapOf("summary" to "done")) },
            observeVerdict = { error("observation must not run when the snapshot is terminal") },
        )

        val report = BridgeRecoveryWorker(attempts, adapter).recover()

        assertThat(report.finalized).isEqualTo(1)
        assertThat(status("attempt-result")).isEqualTo(AgentAttemptStatus.SUCCEEDED)
        assertThat(adapter.reconcileCalls.get()).isEqualTo(1)
        assertThat(adapter.observeCalls.get()).isZero()
        assertThat(adapter.startTurns).isEmpty()
    }

    @Test
    fun `a live lease owned by another worker is never stolen`() {
        attempts.register(scope, attempt("attempt-live"))
        claimToRunning("attempt-live", "live-worker", leaseTtlMs = 60_000)
        val adapter = FakeAdapter(
            reconcileVerdict = { AgentOsExecutionVerdict.Succeeded(emptyMap()) },
            observeVerdict = { error("observation must not run") },
        )

        val report = BridgeRecoveryWorker(attempts, adapter).recover()

        assertThat(report.conflicted).isEqualTo(1)
        assertThat(status("attempt-live")).isEqualTo(AgentAttemptStatus.RUNNING)
    }

    @Test
    fun `a turn is re-driven only when it was never accepted`() {
        attempts.register(scope, attempt("attempt-redrive", brief = "recover me"))
        // Still `pending`: never claimed, so `startTurn` could not have been accepted.
        val adapter = FakeAdapter(
            reconcileVerdict = { AgentOsExecutionVerdict.Indeterminate("no events yet") },
            observeVerdict = { AgentOsExecutionVerdict.Succeeded(mapOf("summary" to "ok")) },
        )

        val report = BridgeRecoveryWorker(attempts, adapter).recover()

        assertThat(report.redriven).isEqualTo(1)
        assertThat(status("attempt-redrive")).isEqualTo(AgentAttemptStatus.SUCCEEDED)
        assertThat(adapter.startTurns).hasSize(1)
        assertThat(adapter.startTurns.single().attemptId).isEqualTo("attempt-redrive")
        assertThat(adapter.startTurns.single().brief).isEqualTo("recover me")
    }

    @Test
    fun `a worker that lost its lease is fenced out of finalization`() {
        attempts.register(scope, attempt("attempt-fenced"))
        claimToRunning("attempt-fenced", "crashed-worker", leaseTtlMs = 0)
        val adapter = FakeAdapter(
            reconcileVerdict = { AgentOsExecutionVerdict.Succeeded(emptyMap()) },
            observeVerdict = { AgentOsExecutionVerdict.Succeeded(emptyMap()) },
        )
        // Recovery rotates the lease to a fresh owner and finalizes.
        BridgeRecoveryWorker(attempts, adapter).recover()
        assertThat(status("attempt-fenced")).isEqualTo(AgentAttemptStatus.SUCCEEDED)

        // The stale crashed worker can no longer finalize: its lease token diverged.
        val failure = assertThrows(AttemptLeaseFencingException::class.java) {
            attempts.finalize(
                scope,
                namespace,
                "wf-recovery",
                "step-1",
                "attempt-fenced",
                "crashed-worker",
                AgentAttemptStatus.SUCCEEDED,
            )
        }
        assertThat(failure.errorCode).isEqualTo("ATTEMPT_LEASE_FENCED")
    }
}
