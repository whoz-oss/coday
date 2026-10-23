package io.whozoss.factory.agentattempt.service

import io.mockk.every
import io.mockk.mockk
import io.mockk.verify
import io.whozoss.factory.adapter.agentos.AgentOsExecutionAdapter
import io.whozoss.factory.adapter.agentos.AgentOsExecutionVerdict
import io.whozoss.factory.agentattempt.domain.AgentAttemptStatus
import io.whozoss.factory.agentattempt.domain.DurableAgentAttempt
import io.whozoss.factory.agentattempt.persistence.ScopedDurableAgentAttempt
import io.whozoss.factory.persistence.TenantScope
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test

/**
 * Unit tests of the legacy-caseId guard in [BridgeRecoveryWorker].
 *
 * Verifies that:
 *  - attempts whose caseId is not a valid UUID (e.g. "case:wf-179", created by
 *    a previous Node.js version of the service) are skipped without calling
 *    [AgentOsExecutionAdapter.reconcile], which would throw
 *    `MethodArgumentTypeMismatchException` because AgentOS expects
 *    `@PathVariable caseId: UUID`;
 *  - attempts with a valid UUID caseId continue the normal reconcile flow;
 *  - [BridgeRecoveryWorker.isValidUuid] correctly classifies both cases.
 *
 * These tests are pure unit tests: no Spring context, no Neo4j harness.
 */
class BridgeRecoveryWorkerLegacyCaseIdTest {

    private val scope = TenantScope("org-test", "ws-test")
    private val namespace = "00000000-0000-4000-8000-000000000001"
    private val workflowId = "workflow-1"

    private val attempts = mockk<DurableAgentAttemptService>()
    private val adapter = mockk<AgentOsExecutionAdapter>()

    private val worker = BridgeRecoveryWorker(
        attempts = attempts,
        adapter = adapter,
        evidenceRepository = null,
        sseHub = null,
    )

    // ---- isValidUuid companion helper -----------------------------------------

    @Test
    fun `isValidUuid returns true for a standard v4 UUID`() {
        assertThat(BridgeRecoveryWorker.isValidUuid("550e8400-e29b-41d4-a716-446655440000")).isTrue()
    }

    @Test
    fun `isValidUuid returns true for a deterministic UUID produced by nameUUIDFromBytes`() {
        // stableCaseId / stableAttemptId use UUID.nameUUIDFromBytes which produces v3 UUIDs
        val uuid = java.util.UUID.nameUUIDFromBytes("workflow-1#step-1".toByteArray()).toString()
        assertThat(BridgeRecoveryWorker.isValidUuid(uuid)).isTrue()
    }

    @Test
    fun `isValidUuid returns false for the legacy pattern case colon wf dash N`() {
        assertThat(BridgeRecoveryWorker.isValidUuid("case:wf-179")).isFalse()
    }

    @Test
    fun `isValidUuid returns false for a plain workflow id like wf-179`() {
        assertThat(BridgeRecoveryWorker.isValidUuid("wf-179")).isFalse()
    }

    @Test
    fun `isValidUuid returns false for a blank string`() {
        assertThat(BridgeRecoveryWorker.isValidUuid("")).isFalse()
    }

    // ---- recovery sweep guard -------------------------------------------------

    @Test
    fun `recover skips an attempt whose caseId is the legacy case-colon-wf pattern`() {
        val legacyAttempt = durableAttempt(caseId = "case:wf-179")
        every { attempts.findNonTerminal(any()) } returns listOf(ScopedDurableAgentAttempt(scope, legacyAttempt))

        val report = worker.recover()

        assertThat(report.scanned).isEqualTo(1)
        assertThat(report.skipped).isEqualTo(1)
        assertThat(report.finalized).isEqualTo(0)
        // The guard must fire BEFORE calling reconcile — no HTTP call to AgentOS.
        verify(exactly = 0) { adapter.reconcile(any<String>()) }
    }

    @Test
    fun `recover skips multiple legacy attempts without calling reconcile`() {
        val legacy = listOf(
            ScopedDurableAgentAttempt(scope, durableAttempt(caseId = "case:wf-1", stepId = "step-a")),
            ScopedDurableAgentAttempt(scope, durableAttempt(caseId = "wf-legacy-id", stepId = "step-b")),
        )
        every { attempts.findNonTerminal(any()) } returns legacy

        val report = worker.recover()

        assertThat(report.scanned).isEqualTo(2)
        assertThat(report.skipped).isEqualTo(2)
        verify(exactly = 0) { adapter.reconcile(any<String>()) }
    }

    @Test
    fun `recover calls reconcile for an attempt with a valid UUID caseId`() {
        val validCaseId = java.util.UUID.randomUUID().toString()
        val validAttempt = durableAttempt(caseId = validCaseId, status = AgentAttemptStatus.RUNNING)
        every { attempts.findNonTerminal(any()) } returns listOf(ScopedDurableAgentAttempt(scope, validAttempt))
        // reconcile returns Indeterminate (not quiescent) so the worker skips finalization
        every { adapter.reconcile(validCaseId) } returns AgentOsExecutionVerdict.Indeterminate(
            reason = "not quiescent",
            evidence = emptyMap(),
        )

        worker.recover()

        verify(exactly = 1) { adapter.reconcile(validCaseId) }
    }

    @Test
    fun `recover counts legacy skips and valid reconciles correctly in a mixed batch`() {
        val validCaseId = java.util.UUID.randomUUID().toString()
        val batch = listOf(
            ScopedDurableAgentAttempt(scope, durableAttempt(caseId = "case:wf-99", stepId = "step-a")),
            ScopedDurableAgentAttempt(scope, durableAttempt(caseId = validCaseId, stepId = "step-b", status = AgentAttemptStatus.RUNNING)),
        )
        every { attempts.findNonTerminal(any()) } returns batch
        every { adapter.reconcile(validCaseId) } returns AgentOsExecutionVerdict.Indeterminate("not quiescent", emptyMap())

        val report = worker.recover()

        assertThat(report.scanned).isEqualTo(2)
        assertThat(report.skipped).isEqualTo(2) // legacy skip + reconcile skip (indeterminate, RUNNING but no finalization)
        verify(exactly = 0) { adapter.reconcile("case:wf-99") }
        verify(exactly = 1) { adapter.reconcile(validCaseId) }
    }

    // ---- helpers ---------------------------------------------------------------

    private fun durableAttempt(
        caseId: String,
        stepId: String = "step-1",
        status: AgentAttemptStatus = AgentAttemptStatus.PENDING,
    ) = DurableAgentAttempt(
        attemptId = java.util.UUID.nameUUIDFromBytes("$workflowId#$stepId".toByteArray()).toString(),
        caseId = caseId,
        namespaceId = namespace,
        workflowId = workflowId,
        stepId = stepId,
        attemptNumber = 1,
        agentName = "agent",
        status = status,
    )
}
