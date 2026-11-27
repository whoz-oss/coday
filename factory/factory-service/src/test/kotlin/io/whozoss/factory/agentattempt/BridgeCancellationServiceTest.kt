package io.whozoss.factory.agentattempt

import io.whozoss.factory.Neo4jDomainIntegrationTest
import io.whozoss.factory.adapter.agentos.AgentOsExecutionAdapter
import io.whozoss.factory.adapter.agentos.AgentOsExecutionVerdict
import io.whozoss.factory.adapter.agentos.CaseHandle
import io.whozoss.factory.adapter.agentos.KillResult
import io.whozoss.factory.agentattempt.domain.AgentAttemptStatus
import io.whozoss.factory.agentattempt.domain.DurableAgentAttempt
import io.whozoss.factory.agentattempt.domain.InvalidAttemptTransitionException
import io.whozoss.factory.agentattempt.service.BridgeCancellationService
import io.whozoss.factory.agentattempt.service.BridgeRecoveryWorker
import io.whozoss.factory.agentattempt.service.DurableAgentAttemptService
import io.whozoss.factory.capability.CapabilityExecutionService
import io.whozoss.factory.error.RevisionConflictException
import io.whozoss.factory.workflow.domain.WorkflowErrorCodes
import io.whozoss.factory.workflow.domain.WorkflowException
import io.whozoss.factory.workflow.domain.WorkflowInstanceRecord
import io.whozoss.factory.workflow.persistence.WorkflowEvidenceRepository
import io.whozoss.factory.workflow.persistence.WorkflowRepository
import io.whozoss.factory.worktree.WorktreeExecutionOccupancyService
import io.whozoss.factory.worktree.domain.WorktreeExecutionOccupancyStatus
import io.whozoss.factory.worktree.persistence.Neo4jWorktreeExecutionOccupancyRepository
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicInteger
import java.util.concurrent.atomic.AtomicReference
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Assertions.assertThrows
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import org.springframework.beans.factory.annotation.Autowired

/**
 * Explicit business cancellation tests (Lot H, step 8; WZ-36379).
 *
 * Cancellation is a first-class command, not a side effect of observation: closing the SSE stream does NOT
 * cancel the run. Only `requestCancel` persists the durable `INTERRUPTED` status under revision fencing and
 * sends the authenticated kill. AgentOS decides who may stop a run: the owner's durable cancel comes first,
 * anyone else's kill is the access check and goes first, writing nothing when refused or unreachable.
 */
class BridgeCancellationServiceTest : Neo4jDomainIntegrationTest() {

    @Autowired
    private lateinit var attempts: DurableAgentAttemptService

    @Autowired
    private lateinit var workflows: WorkflowRepository

    @Autowired
    private lateinit var evidence: WorkflowEvidenceRepository

    @Autowired
    private lateinit var occupations: Neo4jWorktreeExecutionOccupancyRepository

    @Autowired
    private lateinit var occupancyService: WorktreeExecutionOccupancyService

    private val namespace = "3c7f9e3f-9e2b-4f1a-8c7d-cancel-bridge"
    private val owner = "run-owner"
    private val admin = "namespace-admin"

    /**
     * Fake AgentOS boundary. [killResult] answers the authenticated kill (per identity through [killResultFor]);
     * [caseStatus] is what the status read reports. [onInterrupt] runs at the very start of the kill, so a test
     * can observe the durable state the command left behind at that exact moment.
     */
    private class FakeAdapter(
        private val killResult: KillResult = KillResult.ACKNOWLEDGED,
        private val onInterrupt: () -> Unit = {},
    ) : AgentOsExecutionAdapter {
        val interruptCalls = AtomicInteger()
        val killCalls = AtomicInteger()
        val reconcileCalls = AtomicInteger()
        val observeCalls = AtomicInteger()
        val abandoned = CopyOnWriteArrayList<String>()
        val interruptIdentities = CopyOnWriteArrayList<String?>()
        val interruptReasons = CopyOnWriteArrayList<String>()
        var killResultFor: (String?) -> KillResult = { killResult }
        val caseStatus = AtomicReference<String?>(null)
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

        override fun interrupt(caseId: String, reason: String, externalUserId: String?): KillResult {
            onInterrupt()
            interruptIdentities.add(externalUserId)
            interruptReasons.add(reason)
            interruptCalls.incrementAndGet()
            val result = killResultFor(externalUserId)
            if (result == KillResult.ACKNOWLEDGED) interrupted.set(true)
            return result
        }

        override fun abandonObservation(caseId: String) {
            abandoned.add(caseId)
        }

        override fun caseStatus(caseId: String, externalUserId: String?): String? = caseStatus.get()

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

    private fun service(adapter: FakeAdapter, withOccupancy: Boolean = false) = BridgeCancellationService(
        attempts,
        adapter,
        workflows,
        worktreeOccupancyService = occupancyService.takeIf { withOccupancy },
        evidenceRepository = evidence,
    )

    private fun stopEvidence(attemptId: String) = evidence.list(scope, namespace, "wf-cancel", "step-1")
        .filter { it.kind == BridgeCancellationService.AGENT_STOP_EVIDENCE_KIND && it.facts["attemptId"] == attemptId }

    // ---- ordering and identity ----

    @Test
    fun `requestCancel interrupts the case and persists INTERRUPTED under revision fencing`() {
        val revision = runningAttempt("attempt-cancel")
        val adapter = FakeAdapter()
        adapter.caseStatus.set("KILLED")

        val outcome = service(adapter).requestCancel(scope, namespace, "wf-cancel", "attempt-cancel", revision, actorId = owner)

        assertThat(outcome.status).isEqualTo(AgentAttemptStatus.INTERRUPTED)
        assertThat(outcome.idempotent).isFalse()
        assertThat(outcome.killResult).isEqualTo(KillResult.ACKNOWLEDGED)
        assertThat(outcome.killProven).isTrue()
        assertThat(outcome.reconciledVerdict).isEqualTo("Interrupted")
        assertThat(adapter.interruptCalls.get()).isEqualTo(1)
        assertThat(adapter.reconcileCalls.get()).isEqualTo(1)
        assertThat(status("attempt-cancel")).isEqualTo(AgentAttemptStatus.INTERRUPTED)
    }

    @Test
    fun `the interruption reason is recorded as a user cancellation`() {
        val revision = runningAttempt("attempt-reason")
        val adapter = FakeAdapter()

        service(adapter).requestCancel(scope, namespace, "wf-cancel", "attempt-reason", revision, "too expensive", actorId = owner)

        assertThat(adapter.interruptReasons.single()).startsWith(CapabilityExecutionService.USER_CANCELLED).contains("too expensive")
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
    fun `the kill carries the identity of the caller`() {
        val revision = runningAttempt("attempt-identity")
        val adapter = FakeAdapter()

        service(adapter).requestCancel(scope, namespace, "wf-cancel", "attempt-identity", revision, actorId = owner)

        assertThat(adapter.interruptIdentities).containsExactly(owner)
    }

    @Test
    fun `a non owner AgentOS lets kill goes kill first, then the durable cancel, and the kill carries their identity`() {
        val revision = runningAttempt("attempt-admin")
        val statusSeenByTheKill = CopyOnWriteArrayList<AgentAttemptStatus>()
        val adapter = FakeAdapter(onInterrupt = { statusSeenByTheKill.add(status("attempt-admin")) })
        adapter.caseStatus.set("KILLED")

        val outcome = service(adapter).requestCancel(scope, namespace, "wf-cancel", "attempt-admin", revision, actorId = admin)

        // The kill is the access check, so it reached AgentOS while the attempt was still running.
        assertThat(statusSeenByTheKill).containsExactly(AgentAttemptStatus.RUNNING)
        assertThat(adapter.interruptIdentities).containsExactly(admin)
        assertThat(outcome.status).isEqualTo(AgentAttemptStatus.INTERRUPTED)
        assertThat(outcome.killProven).isTrue()
        assertThat(status("attempt-admin")).isEqualTo(AgentAttemptStatus.INTERRUPTED)
        assertThat(attempts.find(scope, namespace, "wf-cancel", "step-1", "attempt-admin")!!.failureCode)
            .isEqualTo(CapabilityExecutionService.USER_CANCELLED)
    }

    @Test
    fun `a non owner AgentOS refuses gets 403 STOP_NOT_AUTHORIZED and nothing is written`() {
        val revision = runningAttempt("attempt-intruder")
        val adapter = FakeAdapter(killResult = KillResult.REFUSED)

        val refused = assertThrows(WorkflowException::class.java) {
            service(adapter).requestCancel(scope, namespace, "wf-cancel", "attempt-intruder", revision, actorId = "someone-else")
        }

        assertThat(refused.statusCode).isEqualTo(403)
        assertThat(refused.errorCode).isEqualTo(WorkflowErrorCodes.STOP_NOT_AUTHORIZED)
        assertThat(status("attempt-intruder")).isEqualTo(AgentAttemptStatus.RUNNING)
        assertThat(adapter.abandoned).isEmpty()
        assertThat(stopEvidence("attempt-intruder")).isEmpty()
    }

    @Test
    fun `a non owner whose kill finds AgentOS unreachable gets 503 STOP_RUNTIME_UNAVAILABLE and nothing is written`() {
        val revision = runningAttempt("attempt-down")
        val adapter = FakeAdapter(killResult = KillResult.UNREACHABLE)

        val unavailable = assertThrows(WorkflowException::class.java) {
            service(adapter).requestCancel(scope, namespace, "wf-cancel", "attempt-down", revision, actorId = "someone-else")
        }

        assertThat(unavailable.statusCode).isEqualTo(503)
        assertThat(unavailable.errorCode).isEqualTo(WorkflowErrorCodes.STOP_RUNTIME_UNAVAILABLE)
        assertThat(status("attempt-down")).isEqualTo(AgentAttemptStatus.RUNNING)
        assertThat(adapter.abandoned).isEmpty()
        assertThat(stopEvidence("attempt-down")).isEmpty()
    }

    @Test
    fun `a stale revision is rejected as a conflict before any kill, for the owner and for anyone else`() {
        val revision = runningAttempt("attempt-stale")
        val adapter = FakeAdapter()

        assertThrows(RevisionConflictException::class.java) {
            service(adapter).requestCancel(scope, namespace, "wf-cancel", "attempt-stale", revision - 1, actorId = owner)
        }
        assertThrows(RevisionConflictException::class.java) {
            service(adapter).requestCancel(scope, namespace, "wf-cancel", "attempt-stale", revision - 1, actorId = admin)
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

    // ---- refused or failed kill, the owner's run is never left running ----

    @Test
    fun `the owner whose kill is refused still has the attempt durably cancelled, the sequencer released and the stop unconfirmed`() {
        val revision = runningAttempt("attempt-refused")
        val adapter = FakeAdapter(killResult = KillResult.REFUSED)

        val outcome = service(adapter).requestCancel(scope, namespace, "wf-cancel", "attempt-refused", revision, actorId = owner)

        assertThat(outcome.killResult).isEqualTo(KillResult.REFUSED)
        assertThat(outcome.killProven).isFalse()
        assertThat(outcome.status).isEqualTo(AgentAttemptStatus.INTERRUPTED)
        assertThat(status("attempt-refused")).isEqualTo(AgentAttemptStatus.INTERRUPTED)
        // The sequencer blocked observing the case is released, so the run leaves `running`.
        assertThat(adapter.abandoned).containsExactly("case:attempt-refused")
        val facts = stopEvidence("attempt-refused").single().facts
        assertThat(facts["killResult"]).isEqualTo("REFUSED")
        assertThat(facts["killProven"]).isEqualTo(false)
        assertThat(facts["actorId"]).isEqualTo(owner)
    }

    @Test
    fun `an unreachable AgentOS also leaves the owner's attempt cancelled and unconfirmed`() {
        val revision = runningAttempt("attempt-down-owner")
        val adapter = FakeAdapter(killResult = KillResult.UNREACHABLE)

        val outcome = service(adapter).requestCancel(scope, namespace, "wf-cancel", "attempt-down-owner", revision, actorId = owner)

        assertThat(outcome.killResult).isEqualTo(KillResult.UNREACHABLE)
        assertThat(outcome.killProven).isFalse()
        assertThat(status("attempt-down-owner")).isEqualTo(AgentAttemptStatus.INTERRUPTED)
        assertThat(adapter.abandoned).containsExactly("case:attempt-down-owner")
    }

    @Test
    fun `an acknowledged kill is proven only once AgentOS reports the case KILLED`() {
        val revision = runningAttempt("attempt-not-yet")
        val adapter = FakeAdapter()
        adapter.caseStatus.set("RUNNING")

        val outcome = service(adapter).requestCancel(scope, namespace, "wf-cancel", "attempt-not-yet", revision, actorId = owner)

        assertThat(outcome.killResult).isEqualTo(KillResult.ACKNOWLEDGED)
        assertThat(outcome.killProven).isFalse()
        assertThat(stopEvidence("attempt-not-yet").single().facts["caseStatus"]).isEqualTo("RUNNING")
    }

    // ---- retry ----

    @Test
    fun `a Stop on an attempt a Stop already cancelled re-sends the kill, proves it and records a new fact`() {
        val revision = runningAttempt("attempt-retry")
        val adapter = FakeAdapter(killResult = KillResult.UNREACHABLE)
        val service = service(adapter)
        service.requestCancel(scope, namespace, "wf-cancel", "attempt-retry", revision, actorId = owner)
        assertThat(adapter.interruptCalls.get()).isEqualTo(1)

        // AgentOS is back: the case is not reported KILLED yet, so the retry sends the kill again, and the case
        // is KILLED once that kill went through.
        adapter.killResultFor = {
            adapter.caseStatus.set("KILLED")
            KillResult.ACKNOWLEDGED
        }
        val retry = service.requestCancel(scope, namespace, "wf-cancel", "attempt-retry", revision + 1, actorId = owner)

        assertThat(retry.idempotent).isTrue()
        assertThat(retry.killResult).isEqualTo(KillResult.ACKNOWLEDGED)
        assertThat(retry.killProven).isTrue()
        assertThat(adapter.interruptCalls.get()).isEqualTo(2)
        assertThat(stopEvidence("attempt-retry").map { it.facts["killProven"] }).containsExactly(false, true)
    }

    @Test
    fun `the owner's retry of a case AgentOS already reports KILLED sends no second kill`() {
        val revision = runningAttempt("attempt-already")
        val adapter = FakeAdapter(killResult = KillResult.UNREACHABLE)
        val service = service(adapter)
        service.requestCancel(scope, namespace, "wf-cancel", "attempt-already", revision, actorId = owner)
        adapter.caseStatus.set("KILLED")

        val retry = service.requestCancel(scope, namespace, "wf-cancel", "attempt-already", revision + 1, actorId = owner)

        assertThat(retry.killProven).isTrue()
        assertThat(adapter.interruptCalls.get()).isEqualTo(1)
    }

    @Test
    fun `a non owner's retry is still authorized by a kill and refused with 403 when AgentOS refuses`() {
        val revision = runningAttempt("attempt-retry-intruder")
        val adapter = FakeAdapter()
        val service = service(adapter)
        service.requestCancel(scope, namespace, "wf-cancel", "attempt-retry-intruder", revision, actorId = owner)
        adapter.killResultFor = { KillResult.REFUSED }

        val refused = assertThrows(WorkflowException::class.java) {
            service.requestCancel(scope, namespace, "wf-cancel", "attempt-retry-intruder", revision + 1, actorId = "someone-else")
        }

        assertThat(refused.statusCode).isEqualTo(403)
        assertThat(refused.errorCode).isEqualTo(WorkflowErrorCodes.STOP_NOT_AUTHORIZED)
    }

    @Test
    fun `the kill sent by the second of two racing Stops finds the attempt already cancelled and stays idempotent`() {
        // Kill-first (non owner) then the durable cancel meets an attempt the sequencer already sealed as the
        // user's Stop: the durable cancel is an accepted no-op, and the Stop concludes normally.
        val revision = runningAttempt("attempt-race")
        val adapter = FakeAdapter(
            onInterrupt = {
                attempts.requestCancel(scope, namespace, "wf-cancel", "step-1", "attempt-race", null, CapabilityExecutionService.USER_CANCELLED)
            },
        )
        adapter.caseStatus.set("KILLED")

        val outcome = service(adapter).requestCancel(scope, namespace, "wf-cancel", "attempt-race", revision, actorId = admin)

        assertThat(outcome.status).isEqualTo(AgentAttemptStatus.INTERRUPTED)
        assertThat(outcome.killProven).isTrue()
        assertThat(status("attempt-race")).isEqualTo(AgentAttemptStatus.INTERRUPTED)
    }

    // ---- worktree occupancy (decision 1A) ----

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
    fun `a proven kill releases the worktree occupancy of the cancelled attempt at once`() {
        val resource = "resource-cancel-released-proven"
        val bound = occupiedAttempt("attempt-release", resource)
        val adapter = FakeAdapter()
        adapter.caseStatus.set("KILLED")

        val outcome = service(adapter, withOccupancy = true)
            .requestCancel(scope, namespace, "wf-cancel", "attempt-release", bound.revision, actorId = owner)

        assertThat(outcome.killProven).isTrue()
        assertThat(occupations.find(resource)!!.status).isEqualTo(WorktreeExecutionOccupancyStatus.RELEASED)
    }

    @Test
    fun `an acknowledged kill whose case is not yet KILLED keeps the occupancy, the sweep releases it once proven`() {
        val resource = "resource-cancel-retained-then-swept"
        val bound = occupiedAttempt("attempt-sweep", resource)
        val adapter = FakeAdapter()
        adapter.caseStatus.set("RUNNING")

        val outcome = service(adapter, withOccupancy = true)
            .requestCancel(scope, namespace, "wf-cancel", "attempt-sweep", bound.revision, actorId = owner)

        assertThat(outcome.killProven).isFalse()
        assertThat(occupations.find(resource)!!.status).isEqualTo(WorktreeExecutionOccupancyStatus.ACTIVE)

        val worker = BridgeRecoveryWorker(
            attempts, adapter, workflowRepository = workflows, worktreeOccupancyService = occupancyService,
        )
        // AgentOS still reports the case running: the sweep keeps the occupancy.
        worker.sweepTerminalOccupancies()
        assertThat(occupations.find(resource)!!.status).isEqualTo(WorktreeExecutionOccupancyStatus.ACTIVE)

        // The kill is now proven: the next sweep frees the worktree.
        adapter.caseStatus.set("KILLED")
        worker.sweepTerminalOccupancies()
        assertThat(occupations.find(resource)!!.status).isEqualTo(WorktreeExecutionOccupancyStatus.RELEASED)
    }

    @Test
    fun `an unproven kill of a refused or unreachable AgentOS keeps the occupancy retained`() {
        val resource = "resource-cancel-retained-refused"
        val bound = occupiedAttempt("attempt-retain-refused", resource)
        val adapter = FakeAdapter(killResult = KillResult.REFUSED)
        adapter.caseStatus.set("KILLED")

        val outcome = service(adapter, withOccupancy = true)
            .requestCancel(scope, namespace, "wf-cancel", "attempt-retain-refused", bound.revision, actorId = owner)

        // Whatever AgentOS reports, a kill it refused is not acknowledged: nothing is proven.
        assertThat(outcome.killProven).isFalse()
        assertThat(status("attempt-retain-refused")).isEqualTo(AgentAttemptStatus.INTERRUPTED)
        assertThat(occupations.find(resource)!!.status).isEqualTo(WorktreeExecutionOccupancyStatus.ACTIVE)
    }

    @Test
    fun `the retry of a Stop releases the occupancy once the kill is finally proven`() {
        val resource = "resource-cancel-released-on-retry"
        val bound = occupiedAttempt("attempt-retry-release", resource)
        val adapter = FakeAdapter(killResult = KillResult.UNREACHABLE)
        val service = service(adapter, withOccupancy = true)
        service.requestCancel(scope, namespace, "wf-cancel", "attempt-retry-release", bound.revision, actorId = owner)
        assertThat(occupations.find(resource)!!.status).isEqualTo(WorktreeExecutionOccupancyStatus.ACTIVE)

        adapter.killResultFor = {
            adapter.caseStatus.set("KILLED")
            KillResult.ACKNOWLEDGED
        }
        service.requestCancel(scope, namespace, "wf-cancel", "attempt-retry-release", bound.revision + 1, actorId = owner)

        assertThat(occupations.find(resource)!!.status).isEqualTo(WorktreeExecutionOccupancyStatus.RELEASED)
    }
}
