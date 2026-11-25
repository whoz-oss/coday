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
import io.whozoss.factory.worktree.WorktreeExecutionOccupancyService
import io.whozoss.factory.worktree.domain.WorktreeExecutionOccupancyStatus
import io.whozoss.factory.worktree.persistence.Neo4jWorktreeExecutionOccupancyRepository
import io.whozoss.factory.workflow.domain.WorkflowInstanceRecord
import io.whozoss.factory.workflow.persistence.WorkflowRepository
import io.whozoss.factory.environment.persistence.WorkEnvironmentRepository
import org.springframework.transaction.PlatformTransactionManager
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

    @Autowired
    private lateinit var workflows: WorkflowRepository

    @Autowired
    private lateinit var occupations: Neo4jWorktreeExecutionOccupancyRepository

    @Autowired
    private lateinit var environments: WorkEnvironmentRepository

    @Autowired
    private lateinit var transactionManager: PlatformTransactionManager

    private val namespace = "3c7f9e3f-9e2b-4f1a-8c7d-cancel-bridge"

    private class FakeAdapter(private val acknowledgeKill: Boolean = true) : AgentOsExecutionAdapter {
        val interruptCalls = AtomicInteger()
        val killCalls = AtomicInteger()
        val reconcileCalls = AtomicInteger()
        val observeCalls = AtomicInteger()
        val interruptIdentities = java.util.concurrent.CopyOnWriteArrayList<String?>()
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
            interruptIdentities.add(externalUserId)
            interrupt(caseId, reason)
            return acknowledgeKill
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

    private fun cancellationService(adapter: FakeAdapter) = BridgeCancellationService(
        attempts,
        adapter,
        workflows,
        WorktreeExecutionOccupancyService(environments, occupations, transactionManager = transactionManager),
    )

    @Test
    fun `the kill carries the persisted workflow actor in preference to the caller`() {
        seedWorkflow("wf-cancel", actor = "persisted-actor")
        attempts.register(scope, attempt("attempt-identity"))
        claimToRunning("attempt-identity")
        val revision = attempts.find(scope, namespace, "wf-cancel", "step-1", "attempt-identity")!!.revision
        val adapter = FakeAdapter()

        cancellationService(adapter).requestCancel(
            scope, namespace, "wf-cancel", "attempt-identity", revision, actorId = "caller-user",
        )

        assertThat(adapter.interruptIdentities).containsExactly("persisted-actor")
    }

    @Test
    fun `the kill falls back to the caller when the workflow has no persisted actor`() {
        attempts.register(scope, attempt("attempt-caller"))
        claimToRunning("attempt-caller")
        val revision = attempts.find(scope, namespace, "wf-cancel", "step-1", "attempt-caller")!!.revision
        val adapter = FakeAdapter()

        cancellationService(adapter).requestCancel(
            scope, namespace, "wf-cancel", "attempt-caller", revision, actorId = "caller-user",
        )

        assertThat(adapter.interruptIdentities).containsExactly("caller-user")
    }

    @Test
    fun `an acknowledged kill releases the worktree occupancy of the cancelled attempt`() {
        val resource = "resource-cancel-released"
        val bound = occupiedAttempt("attempt-release", resource)
        val adapter = FakeAdapter(acknowledgeKill = true)

        val outcome = cancellationService(adapter).requestCancel(
            scope, namespace, "wf-cancel", "attempt-release", bound.revision, actorId = "caller-user",
        )

        assertThat(outcome.killAcknowledged).isTrue()
        assertThat(status("attempt-release")).isEqualTo(AgentAttemptStatus.INTERRUPTED)
        assertThat(occupations.find(resource)!!.status).isEqualTo(WorktreeExecutionOccupancyStatus.RELEASED)
    }

    @Test
    fun `an unacknowledged kill keeps the worktree occupancy retained`() {
        val resource = "resource-cancel-retained"
        val bound = occupiedAttempt("attempt-retain", resource)
        val adapter = FakeAdapter(acknowledgeKill = false)

        val outcome = cancellationService(adapter).requestCancel(
            scope, namespace, "wf-cancel", "attempt-retain", bound.revision, actorId = "caller-user",
        )

        assertThat(outcome.killAcknowledged).isFalse()
        assertThat(status("attempt-retain")).isEqualTo(AgentAttemptStatus.INTERRUPTED)
        assertThat(occupations.find(resource)!!.status).isEqualTo(WorktreeExecutionOccupancyStatus.ACTIVE)
    }
}
