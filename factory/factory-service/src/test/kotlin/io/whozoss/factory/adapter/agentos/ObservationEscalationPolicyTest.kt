package io.whozoss.factory.adapter.agentos

import java.util.concurrent.atomic.AtomicInteger
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test

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

        private fun next(results: MutableList<AgentOsExecutionVerdict>, fallback: AgentOsExecutionVerdict) =
            if (results.isEmpty()) fallback else results.removeAt(0)

        override fun createOrRecoverExecution(namespaceId: String, workflowId: String, stepId: String, externalUserId: String?, attemptId: String, capabilityToken: String?, caseId: String, parentCaseId: String?) = CaseHandle(caseId, namespaceId, false)
        override fun startTurn(caseId: String, persona: String, brief: String, externalUserId: String?, attemptId: String, capabilityToken: String?) = Unit
        override fun observeTurn(caseId: String, attemptId: String, timeoutMs: Long): AgentOsExecutionVerdict { observeCalls.incrementAndGet(); return next(observeResults, defaultObserve) }
        override fun reconcile(caseId: String): AgentOsExecutionVerdict { reconcileCalls.incrementAndGet(); return next(reconcileResults, defaultReconcile) }
        override fun interrupt(caseId: String, reason: String) = Unit
        override fun kill(caseId: String) { killCalls.incrementAndGet() }
    }

    private fun indeterminate(reason: String = "SSE observation timeout") = AgentOsExecutionVerdict.Indeterminate(reason)

    @Test fun `missing structured result is a non destructive contract error`() {
        val adapter = RecordingAdapter()
        val escalation = ObservationEscalationPolicy().escalate(
            adapter,
            "case-1",
            "attempt-1",
            indeterminate(VerdictDeriver.AGENT_NO_STRUCTURED_RESULT),
        )

        assertThat(escalation.verdict).isInstanceOf(AgentOsExecutionVerdict.Indeterminate::class.java)
        assertThat(escalation.steps).isEmpty()
        assertThat(adapter.reconcileCalls.get()).isZero()
        assertThat(adapter.observeCalls.get()).isZero()
        assertThat(adapter.killCalls.get()).isZero()
    }

    @Test fun `rest snapshot proof short-circuits the chain`() {
        val adapter = RecordingAdapter(reconcileResults = mutableListOf(AgentOsExecutionVerdict.Succeeded(mapOf("summary" to "ok"))))
        val escalation = ObservationEscalationPolicy().escalate(adapter, "case-1", "attempt-1", indeterminate())
        assertThat(escalation.verdict).isInstanceOf(AgentOsExecutionVerdict.Succeeded::class.java)
        assertThat(escalation.steps).containsExactly(ObservationEscalationPolicy.STEP_SNAPSHOT)
        assertThat(adapter.killCalls.get()).isZero()
    }

    @Test fun `waiting human snapshot parks without reconnect or kill`() {
        val adapter = RecordingAdapter(reconcileResults = mutableListOf(AgentOsExecutionVerdict.WaitingHuman("question-1", "Proceed?")))
        val escalation = ObservationEscalationPolicy().escalate(adapter, "case-1", "attempt-1", indeterminate())
        assertThat(escalation.verdict).isInstanceOf(AgentOsExecutionVerdict.WaitingHuman::class.java)
        assertThat(escalation.steps).containsExactly(ObservationEscalationPolicy.STEP_SNAPSHOT)
        assertThat(adapter.observeCalls.get()).isZero()
        assertThat(adapter.killCalls.get()).isZero()
    }

    @Test fun `sse reconnection proof is used before any kill`() {
        val adapter = RecordingAdapter(reconcileResults = mutableListOf(indeterminate("still-running")), observeResults = mutableListOf(AgentOsExecutionVerdict.Failed("AGENT_CASE_ERROR", "boom")))
        val escalation = ObservationEscalationPolicy().escalate(adapter, "case-1", "attempt-1", indeterminate())
        assertThat(escalation.verdict).isInstanceOf(AgentOsExecutionVerdict.Failed::class.java)
        assertThat(adapter.killCalls.get()).isZero()
    }

    @Test fun `kill followed by post-kill reconcile yields proof`() {
        val adapter = RecordingAdapter(reconcileResults = mutableListOf(indeterminate("running"), AgentOsExecutionVerdict.Interrupted("killed")), observeResults = mutableListOf(indeterminate("still-running")))
        val escalation = ObservationEscalationPolicy().escalate(adapter, "case-1", "attempt-1", indeterminate())
        assertThat(escalation.verdict).isInstanceOf(AgentOsExecutionVerdict.Interrupted::class.java)
        assertThat(adapter.killCalls.get()).isEqualTo(1)
    }

    @Test fun `without proof verdict stays indeterminate`() {
        val adapter = RecordingAdapter()
        val escalation = ObservationEscalationPolicy().escalate(adapter, "case-1", "attempt-1", indeterminate())
        assertThat(escalation.verdict).isInstanceOf(AgentOsExecutionVerdict.Indeterminate::class.java)
        assertThat(adapter.killCalls.get()).isEqualTo(1)
    }

    @Test fun `kill is skipped when timeout policy forbids it`() {
        val adapter = RecordingAdapter()
        val escalation = ObservationEscalationPolicy(killOnTimeout = false).escalate(adapter, "case-1", "attempt-1", indeterminate())
        assertThat(escalation.verdict).isInstanceOf(AgentOsExecutionVerdict.Indeterminate::class.java)
        assertThat(adapter.killCalls.get()).isZero()
    }
}
