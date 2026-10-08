package io.whozoss.factory.agentattempt

import io.whozoss.factory.Neo4jDomainIntegrationTest
import io.whozoss.factory.adapter.agentos.AgentOsExecutionAdapter
import io.whozoss.factory.adapter.agentos.AgentOsExecutionVerdict
import io.whozoss.factory.adapter.agentos.CaseHandle
import io.whozoss.factory.adapter.agentos.CaseEventView
import io.whozoss.factory.agentattempt.domain.AgentAttemptStatus
import io.whozoss.factory.agentattempt.domain.AttemptLeaseFencingException
import io.whozoss.factory.agentattempt.domain.DurableAgentAttempt
import io.whozoss.factory.agentattempt.service.BridgeRecoveryWorker
import io.whozoss.factory.agentattempt.service.DurableAgentAttemptService
import java.util.UUID
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

    private data class CreateCall(
        val caseId: String,
        val attemptId: String,
        val parentCaseId: String?,
    )

    private class FakeAdapter(
        private val reconcileVerdict: (String) -> AgentOsExecutionVerdict,
        private val observeVerdict: (String) -> AgentOsExecutionVerdict,
        private val history: List<CaseEventView> = emptyList(),
    ) : AgentOsExecutionAdapter {
        val startTurns = CopyOnWriteArrayList<StartTurn>()
        val createCalls = CopyOnWriteArrayList<CreateCall>()
        val reconcileCalls = AtomicInteger()
        val observeCalls = AtomicInteger()
        val createCallCount = AtomicInteger()

        override fun createOrRecoverExecution(
            namespaceId: String,
            workflowId: String,
            stepId: String,
            externalUserId: String?,
            attemptId: String,
            capabilityToken: String?,
            caseId: String,
            parentCaseId: String?,
        ): CaseHandle {
            createCallCount.incrementAndGet()
            createCalls.add(CreateCall(caseId, attemptId, parentCaseId))
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

        override fun persistedEvents(caseId: String): List<CaseEventView> = history

        override fun interrupt(caseId: String, reason: String) = Unit

        override fun kill(caseId: String) = Unit
    }

    private fun caseIdFor(attemptId: String): String =
        UUID.nameUUIDFromBytes("case:$attemptId".toByteArray()).toString()

    private fun attempt(
        attemptId: String,
        brief: String? = "do the thing",
        parentCaseId: String? = null,
    ): DurableAgentAttempt = DurableAgentAttempt(
        attemptId = attemptId,
        caseId = caseIdFor(attemptId),
        namespaceId = namespace,
        workflowId = "wf-recovery",
        stepId = "step-1",
        attemptNumber = 1,
        agentName = "architect",
        brief = brief,
        parentCaseId = parentCaseId,
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
    fun `a re-driven turn preserves the case-family parentCaseId`() {
        val root = caseIdFor("root-attempt")
        attempts.register(scope, attempt("attempt-redrive-child", brief = "recover me", parentCaseId = root))
        val adapter = FakeAdapter(
            reconcileVerdict = { AgentOsExecutionVerdict.Indeterminate("no events yet") },
            observeVerdict = { AgentOsExecutionVerdict.Succeeded(mapOf("summary" to "ok")) },
        )

        val report = BridgeRecoveryWorker(attempts, adapter).recover()

        assertThat(report.redriven).isEqualTo(1)
        val create = adapter.createCalls.single()
        assertThat(create.caseId).isEqualTo(caseIdFor("attempt-redrive-child"))
        assertThat(create.attemptId).isEqualTo("attempt-redrive-child")
        assertThat(create.parentCaseId).isEqualTo(root)
    }

    @Test
    fun `waiting human resumes only from persisted correlated answer`() {
        attempts.register(scope, attempt("attempt-answered"))
        claimToRunning("attempt-answered", "crashed-worker", leaseTtlMs = 0)
        attempts.transition(scope, namespace, "wf-recovery", "step-1", "attempt-answered", "crashed-worker", AgentAttemptStatus.WAITING_HUMAN)
        // claimToRunning already created an immediately expired lease. The
        // attempt remains WAITING_HUMAN and is therefore safely preemptible by
        // the recovery owner without any second transition in the fixture.
        val caseId = caseIdFor("attempt-answered")
        val adapter = FakeAdapter(
            reconcileVerdict = { AgentOsExecutionVerdict.WaitingHuman("question-1") },
            observeVerdict = { error("observation must not run") },
            history = listOf(
                CaseEventView("question-1", CaseEventView.QUESTION_EVENT, caseId, null, mapOf("id" to "question-1", "type" to CaseEventView.QUESTION_EVENT, "caseId" to caseId)),
                CaseEventView("answer-1", CaseEventView.ANSWER_EVENT, caseId, null, mapOf("id" to "answer-1", "type" to CaseEventView.ANSWER_EVENT, "caseId" to caseId, "questionId" to "question-1")),
            ),
        )

        val report = BridgeRecoveryWorker(attempts, adapter).recover()

        assertThat(report.resumed).isEqualTo(1)
        assertThat(status("attempt-answered")).isEqualTo(AgentAttemptStatus.RUNNING)
        assertThat(attempts.find(scope, namespace, "wf-recovery", "step-1", "attempt-answered")!!.lastObservedEventId)
            .isEqualTo("answer-1")
    }

    @Test
    fun `waiting human remains waiting without persisted correlated answer`() {
        attempts.register(scope, attempt("attempt-unanswered"))
        claimToRunning("attempt-unanswered", "crashed-worker", leaseTtlMs = 0)
        attempts.transition(scope, namespace, "wf-recovery", "step-1", "attempt-unanswered", "crashed-worker", AgentAttemptStatus.WAITING_HUMAN)
        val caseId = caseIdFor("attempt-unanswered")
        val adapter = FakeAdapter(
            reconcileVerdict = { AgentOsExecutionVerdict.WaitingHuman("question-1") },
            observeVerdict = { error("observation must not run") },
            history = listOf(
                CaseEventView("question-1", CaseEventView.QUESTION_EVENT, caseId, null, mapOf("id" to "question-1", "type" to CaseEventView.QUESTION_EVENT, "caseId" to caseId)),
            ),
        )

        val report = BridgeRecoveryWorker(attempts, adapter).recover()

        assertThat(report.finalized).isEqualTo(1)
        assertThat(status("attempt-unanswered")).isEqualTo(AgentAttemptStatus.WAITING_HUMAN)
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
