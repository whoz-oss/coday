package io.whozoss.factory.agentattempt

import io.whozoss.factory.Neo4jDomainIntegrationTest
import io.whozoss.factory.adapter.agentos.AgentOsExecutionAdapter
import io.whozoss.factory.adapter.agentos.AgentOsExecutionVerdict
import io.whozoss.factory.adapter.agentos.CaseHandle
import io.whozoss.factory.agentattempt.domain.AgentAttemptStatus
import io.whozoss.factory.agentattempt.domain.DurableAgentAttempt
import io.whozoss.factory.agentattempt.domain.InvalidAttemptTransitionException
import io.whozoss.factory.agentattempt.service.BridgeCancellationService
import io.whozoss.factory.agentattempt.service.DurableAgentAttemptService
import io.whozoss.factory.error.RevisionConflictException
import io.whozoss.factory.worktree.WorktreeExecutionOccupancyService
import io.whozoss.factory.worktree.domain.WorktreeExecutionOccupancyStatus
import io.whozoss.factory.worktree.persistence.Neo4jWorktreeExecutionOccupancyRepository
import io.whozoss.factory.workflow.domain.WorkflowErrorCodes
import io.whozoss.factory.workflow.domain.WorkflowException
import io.whozoss.factory.workflow.domain.WorkflowInstanceRecord
import io.whozoss.factory.workflow.persistence.WorkflowRepository
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicInteger
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Assertions.assertThrows
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import org.springframework.beans.factory.annotation.Autowired

/**
 * Explicit business cancellation tests (Lot H, step 8; WZ-36379).
 *
 * Cancellation is a first-class command, not a side effect of observation:
 * closing the SSE stream does NOT cancel the run. Only `requestCancel`, issued by the user who
 * launched the run, persists the durable `INTERRUPTED` status under revision fencing and THEN
 * issues the authenticated interrupt/kill and reconciles the post-kill state.
 */
class BridgeCancellationServiceTest : Neo4jDomainIntegrationTest() {

    @Autowired
    private lateinit var attempts: DurableAgentAttemptService

    @Autowired
    private lateinit var workflows: WorkflowRepository

    @Autowired
    private lateinit var occupations: Neo4jWorktreeExecutionOccupancyRepository

    private val namespace = "3c7f9e3f-9e2b-4f1a-8c7d-cancel-bridge"
    private val owner = "run-owner"

    /**
     * Fake AgentOS boundary. [onInterrupt] runs at the very start of the authenticated kill, so a
     * test can observe the durable state the command left behind at that exact moment.
     */
    private class FakeAdapter(
        private val acknowledgeKill: Boolean = true,
        private val onInterrupt: () -> Unit = {},
    ) : AgentOsExecutionAdapter {
        val interruptCalls = AtomicInteger()
        val killCalls = AtomicInteger()
        val reconcileCalls = AtomicInteger()
        val observeCalls = AtomicInteger()
        val interruptIdentities = CopyOnWriteArrayList<String?>()
        private val interrupted = AtomicBoolean(false)

        override fun createOrRecoverExecution(
            namespaceId: String,
            workflowId: String,
            stepId: String,
            externalUserId: String?,
            attemptId: String,
            capabilityToken: String?,
            caseId: String,
            parentCaseId: String?,
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

        override fun interrupt(caseId: String, reason: String, externalUserId: String?): Boolean {
            onInterrupt()
            interruptIdentities.add(externalUserId)
            interrupt(caseId, reason)
            return acknowledgeKill
        }

        override fun kill(caseId: String) {
            killCalls.incrementAndGet()
            interrupted.set(true)
        }
    }

    @BeforeEach
    fun seedRunOwnedBy() {
        seedWorkflow("wf-cancel", actor = owner)
    }

    private fun seedWorkflow(workflowId: String, actor: String?) {
        workflows.insertInstance(
            scope,
            WorkflowInstanceRecord(
                namespaceId = namespace,
                workflowId = workflowId,
                revision = 1,
                status = "active",
                creationCommandHash = "cancel-$workflowId",
                instance = actor?.let { mapOf("controllerRequest" to mapOf("actorId" to it)) } ?: emptyMap(),
                projection = emptyMap(),
            ),
        )
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

    private fun runningAttempt(attemptId: String): Int {
        attempts.register(scope, attempt(attemptId))
        claimToRunning(attemptId)
        return attempts.find(scope, namespace, "wf-cancel", "step-1", attemptId)!!.revision
    }

    private fun status(attemptId: String) = attempts.find(scope, namespace, "wf-cancel", "step-1", attemptId)!!.status

    private fun service(adapter: FakeAdapter) = BridgeCancellationService(attempts, adapter, workflows)

    @Test
    fun `requestCancel interrupts the case and persists INTERRUPTED under revision fencing`() {
        val revision = runningAttempt("attempt-cancel")
        val adapter = FakeAdapter()

        val outcome = service(adapter).requestCancel(scope, namespace, "wf-cancel", "attempt-cancel", revision, actorId = owner)

        assertThat(outcome.status).isEqualTo(AgentAttemptStatus.INTERRUPTED)
        assertThat(outcome.idempotent).isFalse()
        assertThat(outcome.killAcknowledged).isTrue()
        assertThat(outcome.reconciledVerdict).isEqualTo("Interrupted")
        assertThat(adapter.interruptCalls.get()).isEqualTo(1)
        assertThat(adapter.reconcileCalls.get()).isEqualTo(1)
        assertThat(status("attempt-cancel")).isEqualTo(AgentAttemptStatus.INTERRUPTED)
    }

    @Test
    fun `the owner gets the durable cancel committed before the authenticated kill is issued`() {
        runningAttempt("attempt-order")
        val statusSeenByTheKill = CopyOnWriteArrayList<AgentAttemptStatus>()
        val adapter = FakeAdapter(onInterrupt = { statusSeenByTheKill.add(status("attempt-order")) })

        service(adapter).requestCancel(scope, namespace, "wf-cancel", "attempt-order", expectedRevision = null, actorId = owner)

        // When the kill reaches AgentOS the attempt is already durably cancelled, so a sequencer that
        // observes the kill can only find itself fenced, never finalize an ordinary failure.
        assertThat(statusSeenByTheKill).containsExactly(AgentAttemptStatus.INTERRUPTED)
    }

    @Test
    fun `the kill carries the run owner identity`() {
        val revision = runningAttempt("attempt-identity")
        val adapter = FakeAdapter()

        service(adapter).requestCancel(scope, namespace, "wf-cancel", "attempt-identity", revision, actorId = owner)

        assertThat(adapter.interruptIdentities).containsExactly(owner)
    }

    @Test
    fun `a caller other than the run owner is refused with 403 before any cancel or kill`() {
        val revision = runningAttempt("attempt-intruder")
        val adapter = FakeAdapter()

        val refused = assertThrows(WorkflowException::class.java) {
            service(adapter).requestCancel(scope, namespace, "wf-cancel", "attempt-intruder", revision, actorId = "someone-else")
        }

        assertThat(refused.statusCode).isEqualTo(403)
        assertThat(refused.errorCode).isEqualTo(WorkflowErrorCodes.ACTION_NOT_ALLOWED)
        assertThat(status("attempt-intruder")).isEqualTo(AgentAttemptStatus.RUNNING)
        assertThat(adapter.interruptCalls.get()).isZero()
        assertThat(adapter.interruptIdentities).isEmpty()
        assertThat(adapter.reconcileCalls.get()).isZero()
    }

    @Test
    fun `a non owner is also refused on the idempotent path`() {
        val revision = runningAttempt("attempt-replay-intruder")
        val adapter = FakeAdapter()
        service(adapter).requestCancel(scope, namespace, "wf-cancel", "attempt-replay-intruder", revision, actorId = owner)

        val refused = assertThrows(WorkflowException::class.java) {
            service(adapter).requestCancel(scope, namespace, "wf-cancel", "attempt-replay-intruder", revision + 1, actorId = "someone-else")
        }

        assertThat(refused.statusCode).isEqualTo(403)
        assertThat(adapter.interruptCalls.get()).isEqualTo(1)
    }

    @Test
    fun `a run with no persisted owner cannot be stopped by anyone`() {
        val revision = runningAttempt("attempt-no-owner")
        workflows.deleteInstance(scope, namespace, "wf-cancel")
        seedWorkflow("wf-cancel", actor = null)
        val adapter = FakeAdapter()

        val refused = assertThrows(WorkflowException::class.java) {
            service(adapter).requestCancel(scope, namespace, "wf-cancel", "attempt-no-owner", revision, actorId = owner)
        }

        assertThat(refused.statusCode).isEqualTo(403)
        assertThat(status("attempt-no-owner")).isEqualTo(AgentAttemptStatus.RUNNING)
        assertThat(adapter.interruptCalls.get()).isZero()
    }

    @Test
    fun `a stale revision is rejected as a conflict before any kill`() {
        val revision = runningAttempt("attempt-stale")
        val adapter = FakeAdapter()

        assertThrows(RevisionConflictException::class.java) {
            service(adapter).requestCancel(scope, namespace, "wf-cancel", "attempt-stale", revision - 1, actorId = owner)
        }
        assertThat(status("attempt-stale")).isEqualTo(AgentAttemptStatus.RUNNING)
        assertThat(adapter.interruptCalls.get()).isZero()
    }

    @Test
    fun `an attempt the sequencer already finalized is rejected as a conflict and nothing is killed`() {
        runningAttempt("attempt-finished")
        attempts.finalize(scope, namespace, "wf-cancel", "step-1", "attempt-finished", "live-worker", AgentAttemptStatus.SUCCEEDED)
        val adapter = FakeAdapter()

        val refused = assertThrows(InvalidAttemptTransitionException::class.java) {
            service(adapter).requestCancel(scope, namespace, "wf-cancel", "attempt-finished", expectedRevision = null, actorId = owner)
        }

        assertThat(refused.errorCode).isEqualTo("ATTEMPT_INVALID_TRANSITION")
        assertThat(status("attempt-finished")).isEqualTo(AgentAttemptStatus.SUCCEEDED)
        assertThat(adapter.interruptCalls.get()).isZero()
    }

    @Test
    fun `cancelling an already interrupted attempt is idempotent`() {
        val revision = runningAttempt("attempt-idem")
        val adapter = FakeAdapter()
        val service = service(adapter)
        service.requestCancel(scope, namespace, "wf-cancel", "attempt-idem", revision, actorId = owner)

        val replay = service.requestCancel(scope, namespace, "wf-cancel", "attempt-idem", revision + 1, actorId = owner)

        assertThat(replay.idempotent).isTrue()
        assertThat(replay.status).isEqualTo(AgentAttemptStatus.INTERRUPTED)
        // The interrupt was issued only once.
        assertThat(adapter.interruptCalls.get()).isEqualTo(1)
    }

    @Test
    fun `closing the SSE stream alone does not cancel the run`() {
        runningAttempt("attempt-observe")
        val adapter = FakeAdapter()

        // Simulate a client that merely observes then disconnects: no command is
        // sent, so the attempt stays running and no interrupt/kill is issued.
        adapter.observeTurn("case:attempt-observe", "attempt-observe", 1_000)

        assertThat(status("attempt-observe")).isEqualTo(AgentAttemptStatus.RUNNING)
        assertThat(adapter.interruptCalls.get()).isZero()
        assertThat(adapter.killCalls.get()).isZero()
    }

    @Test
    fun `a refused kill still leaves the attempt durably cancelled and is reported as not acknowledged`() {
        val revision = runningAttempt("attempt-refused")
        val adapter = FakeAdapter(acknowledgeKill = false)

        val outcome = service(adapter).requestCancel(scope, namespace, "wf-cancel", "attempt-refused", revision, actorId = owner)

        assertThat(outcome.killAcknowledged).isFalse()
        assertThat(outcome.status).isEqualTo(AgentAttemptStatus.INTERRUPTED)
        assertThat(status("attempt-refused")).isEqualTo(AgentAttemptStatus.INTERRUPTED)
    }

    private fun occupiedAttempt(attemptId: String, resource: String): DurableAgentAttempt {
        attempts.register(scope, attempt(attemptId))
        claimToRunning(attemptId)
        val ownerId = WorktreeExecutionOccupancyService.agentOwnerId(namespace, "wf-cancel", "step-1", attemptId)
        val occupation = occupations.acquire(scope, resource, WorktreeExecutionOccupancyService.AGENT_OWNER_KIND, ownerId)
        val current = attempts.find(scope, namespace, "wf-cancel", "step-1", attemptId)!!
        return attempts.bindOccupancy(
            scope, current, occupation.resourceIdentity, WorktreeExecutionOccupancyService.AGENT_OWNER_KIND, ownerId, occupation.generation,
        )
    }

    @Test
    fun `an acknowledged kill leaves the worktree occupancy of the cancelled attempt retained`() {
        val resource = "resource-cancel-retained-acknowledged"
        val bound = occupiedAttempt("attempt-retain-ack", resource)
        val adapter = FakeAdapter(acknowledgeKill = true)

        val outcome = service(adapter).requestCancel(scope, namespace, "wf-cancel", "attempt-retain-ack", bound.revision, actorId = owner)

        assertThat(outcome.killAcknowledged).isTrue()
        assertThat(status("attempt-retain-ack")).isEqualTo(AgentAttemptStatus.INTERRUPTED)
        // AgentOS only marks the case KILLED: it gives no proof that a tool still running in the
        // worktree has stopped, so the occupancy is retained (fail-closed), never released here.
        assertThat(occupations.find(resource)!!.status).isEqualTo(WorktreeExecutionOccupancyStatus.ACTIVE)
    }

    @Test
    fun `an unacknowledged kill also keeps the worktree occupancy retained`() {
        val resource = "resource-cancel-retained-refused"
        val bound = occupiedAttempt("attempt-retain-refused", resource)
        val adapter = FakeAdapter(acknowledgeKill = false)

        val outcome = service(adapter).requestCancel(scope, namespace, "wf-cancel", "attempt-retain-refused", bound.revision, actorId = owner)

        assertThat(outcome.killAcknowledged).isFalse()
        assertThat(status("attempt-retain-refused")).isEqualTo(AgentAttemptStatus.INTERRUPTED)
        assertThat(occupations.find(resource)!!.status).isEqualTo(WorktreeExecutionOccupancyStatus.ACTIVE)
    }
}
