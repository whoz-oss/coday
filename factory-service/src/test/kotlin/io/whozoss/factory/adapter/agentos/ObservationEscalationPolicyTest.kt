package io.whozoss.factory.adapter.agentos

import java.util.concurrent.atomic.AtomicInteger
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test

/**
 * Unit tests of the observation timeout escalation chain (Lot H, step 8).
 *
 * The chain must be deterministic — REST snapshot, then SSE reconnection, then
 * (only when the policy allows) kill, then post-kill reconciliation — and it must
 * NEVER derive a success from silence.
 */
class ObservationEscalationPolicyTest {

    private class RecordingAdapter(
        private val reconcileResults: MutableList<AgentOsExecutionVerdict> = mutableListOf(),
        private val defaultReconcile: AgentOsExecutionVerdict = AgentOsExecutionVerdict.Indeterminate("not-quiescent"),
        private val observeResults: MutableList<AgentOsExecutionVerdict> = mutableListOf(),
        private val defaultObserve: AgentOsExecutionVerdict = AgentOsExecutionVerdict.Indeterminate("observation-timeout"),
    ) : AgentOsExecutionAdapter {
        val reconcileCalls = AtomicInteger()
        val observeCalls = AtomicInteger()
        val killCalls = AtomicInteger()
        val interruptCalls = AtomicInteger()

        private fun next(results: MutableList<AgentOsExecutionVerdict>, fallback: AgentOsExecutionVerdict): AgentOsExecutionVerdict =
            if (results.isEmpty()) fallback else results.removeAt(0)

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
            return next(observeResults, defaultObserve)
        }

        override fun reconcile(caseId: String): AgentOsExecutionVerdict {
            reconcileCalls.incrementAndGet()
            return next(reconcileResults, defaultReconcile)
        }

        override fun interrupt(caseId: String, reason: String) {
            interruptCalls.incrementAndGet()
        }

        override fun kill(caseId: String) {
            killCalls.incrementAndGet()
        }
    }

    private fun indeterminate(reason: String = "SSE observation timeout") = AgentOsExecutionVerdict.Indeterminate(reason)

    @Test
    fun `rest snapshot proof short-circuits the chain`() {
        val adapter = RecordingAdapter(reconcileResults = mutableListOf(AgentOsExecutionVerdict.Succeeded(mapOf("summary" to "ok"))))
        val policy = ObservationEscalationPolicy()

        val escalation = policy.escalate(adapter, "case-1", "attempt-1", indeterminate())

        assertThat(escalation.verdict).isInstanceOf(AgentOsExecutionVerdict.Succeeded::class.java)
        assertThat(escalation.steps).containsExactly(ObservationEscalationPolicy.STEP_SNAPSHOT)
        assertThat(adapter.reconcileCalls.get()).isEqualTo(1)
        assertThat(adapter.observeCalls.get()).isZero()
        assertThat(adapter.killCalls.get()).isZero()
    }

    @Test
    fun `sse reconnection proof is used before any kill`() {
        val adapter = RecordingAdapter(
            reconcileResults = mutableListOf(indeterminate("still-running")),
            observeResults = mutableListOf(AgentOsExecutionVerdict.Failed("AGENT_CASE_ERROR", "boom")),
        )
        val policy = ObservationEscalationPolicy()

        val escalation = policy.escalate(adapter, "case-1", "attempt-1", indeterminate())

        assertThat(escalation.verdict).isInstanceOf(AgentOsExecutionVerdict.Failed::class.java)
        assertThat(escalation.steps).containsExactly(
            ObservationEscalationPolicy.STEP_SNAPSHOT,
            ObservationEscalationPolicy.STEP_RECONNECT,
        )
        assertThat(adapter.killCalls.get()).isZero()
    }

    @Test
    fun `kill followed by a post-kill reconcile yields the proof`() {
        val adapter = RecordingAdapter(
            // snapshot: not quiescent; post-kill: interrupted.
            reconcileResults = mutableListOf(indeterminate("running"), AgentOsExecutionVerdict.Interrupted("killed")),
            observeResults = mutableListOf(indeterminate("still-running")),
        )
        val policy = ObservationEscalationPolicy()

        val escalation = policy.escalate(adapter, "case-1", "attempt-1", indeterminate())

        assertThat(escalation.verdict).isInstanceOf(AgentOsExecutionVerdict.Interrupted::class.java)
        assertThat(escalation.steps).containsExactly(
            ObservationEscalationPolicy.STEP_SNAPSHOT,
            ObservationEscalationPolicy.STEP_RECONNECT,
            ObservationEscalationPolicy.STEP_KILL,
            ObservationEscalationPolicy.STEP_POST_KILL_RECONCILE,
        )
        assertThat(adapter.killCalls.get()).isEqualTo(1)
        assertThat(adapter.reconcileCalls.get()).isEqualTo(2)
    }

    @Test
    fun `without sufficient proof the verdict stays indeterminate and never succeeds`() {
        val adapter = RecordingAdapter()
        val policy = ObservationEscalationPolicy()

        val escalation = policy.escalate(adapter, "case-1", "attempt-1", indeterminate())

        assertThat(escalation.verdict).isInstanceOf(AgentOsExecutionVerdict.Indeterminate::class.java)
        assertThat(escalation.verdict).isNotInstanceOf(AgentOsExecutionVerdict.Succeeded::class.java)
        assertThat((escalation.verdict as AgentOsExecutionVerdict.Indeterminate).evidence["escalated"]).isEqualTo(true)
        assertThat(adapter.killCalls.get()).isEqualTo(1)
    }

    @Test
    fun `kill is skipped when the timeout policy forbids it`() {
        val adapter = RecordingAdapter()
        val policy = ObservationEscalationPolicy(killOnTimeout = false)

        val escalation = policy.escalate(adapter, "case-1", "attempt-1", indeterminate())

        assertThat(escalation.verdict).isInstanceOf(AgentOsExecutionVerdict.Indeterminate::class.java)
        assertThat(escalation.steps).doesNotContain(ObservationEscalationPolicy.STEP_KILL)
        assertThat(adapter.killCalls.get()).isZero()
    }
}
