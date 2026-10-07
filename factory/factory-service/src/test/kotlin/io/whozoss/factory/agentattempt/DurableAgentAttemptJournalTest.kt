package io.whozoss.factory.agentattempt

import io.whozoss.factory.Neo4jDomainIntegrationTest
import io.whozoss.factory.agentattempt.domain.AgentAttemptStatus
import io.whozoss.factory.agentattempt.domain.AttemptLeaseFencingException
import io.whozoss.factory.agentattempt.domain.DurableAgentAttempt
import io.whozoss.factory.agentattempt.service.DurableAgentAttemptService
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Assertions.assertThrows
import org.junit.jupiter.api.Test
import org.springframework.beans.factory.annotation.Autowired

/**
 * Req 1 attestation: the durable attempt carries an append-only transition
 * journal, and crash recovery on top of the durable record is complete.
 *
 * The journal half is proven here: every landed state change (registration,
 * claim, transition, finalization) appends exactly one ordered entry with a
 * monotone `sequence`, the correct `fromStatus`/`toStatus` pair and a strictly
 * increasing `revisionAfter`; a fenced mutation that matched nothing appends
 * nothing.
 *
 * The recovery half is proven by
 * [io.whozoss.factory.agentattempt.BridgeRecoveryWorkerTest] (reconcile ->
 * finalize, resume-without-second-turn, live-lease-never-stolen,
 * re-drive-only-when-never-accepted, waiting-human resume) and by the recovery
 * cases of `DurableAgentOsBridgeIntegrationTest` — deliberately not duplicated
 * here.
 */
class DurableAgentAttemptJournalTest : Neo4jDomainIntegrationTest() {

    @Autowired
    private lateinit var service: DurableAgentAttemptService

    @Test
    fun `every landed state change appends one ordered journal entry`() {
        service.register(scope, attempt("attempt-journal"))
        service.claim(
            scope,
            namespaceId = NAMESPACE_ID,
            workflowId = WORKFLOW_ID,
            stepId = STEP_ID,
            attemptId = "attempt-journal",
            ownerToken = "owner-a",
            leaseTtlMs = 60_000,
        )
        service.transition(
            scope,
            namespaceId = NAMESPACE_ID,
            workflowId = WORKFLOW_ID,
            stepId = STEP_ID,
            attemptId = "attempt-journal",
            ownerToken = "owner-a",
            target = AgentAttemptStatus.STARTING,
        )
        service.transition(
            scope,
            namespaceId = NAMESPACE_ID,
            workflowId = WORKFLOW_ID,
            stepId = STEP_ID,
            attemptId = "attempt-journal",
            ownerToken = "owner-a",
            target = AgentAttemptStatus.RUNNING,
        )
        service.finalize(
            scope,
            namespaceId = NAMESPACE_ID,
            workflowId = WORKFLOW_ID,
            stepId = STEP_ID,
            attemptId = "attempt-journal",
            ownerToken = "owner-a",
            target = AgentAttemptStatus.SUCCEEDED,
            resultEvidenceId = "evidence-1",
            lastObservedEventId = "evt-7",
        )

        val journal = service.journal(scope, NAMESPACE_ID, WORKFLOW_ID, STEP_ID, "attempt-journal")

        // Registration + claim + starting + running + succeeded, in order.
        assertThat(journal.map { it.toStatus }).containsExactly(
            AgentAttemptStatus.PENDING,
            AgentAttemptStatus.CLAIMING,
            AgentAttemptStatus.STARTING,
            AgentAttemptStatus.RUNNING,
            AgentAttemptStatus.SUCCEEDED,
        )
        assertThat(journal.map { it.fromStatus }).containsExactly(
            null,
            AgentAttemptStatus.PENDING,
            AgentAttemptStatus.CLAIMING,
            AgentAttemptStatus.STARTING,
            AgentAttemptStatus.RUNNING,
        )
        // Monotone sequence, strictly increasing revision, structured refs kept.
        assertThat(journal.map { it.sequence }).containsExactly(1L, 2L, 3L, 4L, 5L)
        assertThat(journal.map { it.revisionAfter }).isSorted
        assertThat(journal.map { it.revisionAfter }.distinct()).hasSize(journal.size)
        journal.forEach { entry ->
            assertThat(entry.attemptId).isEqualTo("attempt-journal")
            assertThat(entry.recordedAt).isNotNull
        }
        val finalizeEntry = journal.last()
        assertThat(finalizeEntry.ownerToken).isEqualTo("owner-a")
        assertThat(finalizeEntry.resultEvidenceId).isEqualTo("evidence-1")
        assertThat(finalizeEntry.lastObservedEventId).isEqualTo("evt-7")

        // The journal mirrors the authoritative snapshot's terminal revision.
        val persisted = service.find(scope, NAMESPACE_ID, WORKFLOW_ID, STEP_ID, "attempt-journal")
        assertThat(persisted!!.revision).isEqualTo(finalizeEntry.revisionAfter)
    }

    @Test
    fun `a fenced finalize appends no journal entry`() {
        service.register(scope, attempt("attempt-fenced"))
        service.claim(
            scope,
            namespaceId = NAMESPACE_ID,
            workflowId = WORKFLOW_ID,
            stepId = STEP_ID,
            attemptId = "attempt-fenced",
            ownerToken = "owner-a",
            leaseTtlMs = 60_000,
        )
        val before = service.journal(scope, NAMESPACE_ID, WORKFLOW_ID, STEP_ID, "attempt-fenced")
        assertThat(before.map { it.toStatus })
            .containsExactly(AgentAttemptStatus.PENDING, AgentAttemptStatus.CLAIMING)

        val failure = assertThrows(AttemptLeaseFencingException::class.java) {
            service.finalize(
                scope,
                namespaceId = NAMESPACE_ID,
                workflowId = WORKFLOW_ID,
                stepId = STEP_ID,
                attemptId = "attempt-fenced",
                ownerToken = "owner-b",
                target = AgentAttemptStatus.SUCCEEDED,
            )
        }
        assertThat(failure.errorCode).isEqualTo("ATTEMPT_LEASE_FENCED")

        // The fenced mutation matched nothing: the journal is untouched.
        val after = service.journal(scope, NAMESPACE_ID, WORKFLOW_ID, STEP_ID, "attempt-fenced")
        assertThat(after).isEqualTo(before)
    }

    @Test
    fun `an idempotent re-registration appends no duplicate journal entry`() {
        service.register(scope, attempt("attempt-replay"))
        service.register(scope, attempt("attempt-replay"))

        val journal = service.journal(scope, NAMESPACE_ID, WORKFLOW_ID, STEP_ID, "attempt-replay")
        assertThat(journal).hasSize(1)
        assertThat(journal.single().toStatus).isEqualTo(AgentAttemptStatus.PENDING)
        assertThat(journal.single().fromStatus).isNull()
    }

    private fun attempt(attemptId: String): DurableAgentAttempt = DurableAgentAttempt(
        attemptId = attemptId,
        caseId = "case-1",
        namespaceId = NAMESPACE_ID,
        workflowId = WORKFLOW_ID,
        stepId = STEP_ID,
        attemptNumber = 1,
        agentName = "builder",
    )

    companion object {
        private const val NAMESPACE_ID = "ns-journal"
        private const val WORKFLOW_ID = "wf-journal"
        private const val STEP_ID = "step-journal"
    }
}
