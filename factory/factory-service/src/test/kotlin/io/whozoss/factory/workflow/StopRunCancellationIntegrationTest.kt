package io.whozoss.factory.workflow

import com.fasterxml.jackson.databind.ObjectMapper
import io.mockk.every
import io.mockk.mockk
import io.mockk.verify
import io.whozoss.factory.Neo4jDomainIntegrationTest
import io.whozoss.factory.adapter.agentos.AgentOsExecutionAdapter
import io.whozoss.factory.adapter.agentos.AgentOsExecutionVerdict
import io.whozoss.factory.adapter.agentos.CaseHandle
import io.whozoss.factory.agentattempt.domain.AgentAttemptStatus
import io.whozoss.factory.agentattempt.domain.AttemptContextEnvelope
import io.whozoss.factory.agentattempt.domain.DurableAgentAttempt
import io.whozoss.factory.agentattempt.domain.InvalidAttemptTransitionException
import io.whozoss.factory.agentattempt.persistence.AgentStepAttemptRepository
import io.whozoss.factory.agentattempt.persistence.SpringDataNeo4jOutboxRepository
import io.whozoss.factory.agentattempt.service.BridgeCancellationService
import io.whozoss.factory.agentattempt.service.DurableAgentAttemptService
import io.whozoss.factory.agentattempt.service.OutboxDrainService
import io.whozoss.factory.agentattempt.service.OutboxDrainWorker
import io.whozoss.factory.capability.CapabilityExecutionService
import io.whozoss.factory.capability.CapabilityResolver
import io.whozoss.factory.proxy.AgentOsProxyClient
import io.whozoss.factory.workflow.domain.ControllerExecutionInput
import io.whozoss.factory.workflow.domain.ControllerRequestInput
import io.whozoss.factory.workflow.domain.WorkflowDefinitionRecord
import io.whozoss.factory.workflow.domain.WorkflowDefinitionValidation
import io.whozoss.factory.workflow.domain.WorkflowDefinitionValidator
import io.whozoss.factory.workflow.domain.WorkflowStartCommand
import io.whozoss.factory.workflow.domain.WorkflowStatuses
import io.whozoss.factory.workflow.domain.WorkflowStepStateRecord
import io.whozoss.factory.workflow.domain.hashWorkflowDefinition
import io.whozoss.factory.workflow.persistence.HumanInteractionRepository
import io.whozoss.factory.workflow.persistence.WorkflowEvidenceRepository
import io.whozoss.factory.workflow.persistence.WorkflowRepository
import io.whozoss.factory.workflow.service.SessionRunResult
import io.whozoss.factory.workflow.service.SessionRunService
import io.whozoss.factory.workflow.service.SessionRunSubmissionService
import io.whozoss.factory.workflow.service.WorkflowService
import io.whozoss.factory.workflow.sse.WorkflowSseHub
import io.whozoss.factory.workspace.WorkspacePrecheckService
import java.nio.file.Path
import java.time.Clock
import java.util.UUID
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.CountDownLatch
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Assertions.assertThrows
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.transaction.PlatformTransactionManager

/**
 * Non-regression of WZ-36379: a user Stop must seal the step AND the run as `cancelled`, whichever
 * side wins the race between the cancel command and the sequencer.
 *
 * Before the fix the cancel rotated the lease owner, the sequencer's finalization was fenced and the
 * step stayed `running` forever (so did the run); with no live sequencer nothing ever replayed the
 * cancelled attempt, and a replay would have classified it as a failure. The real Spring beans and a
 * stateful fake of the AgentOS boundary are used, as in `DurableAgentOsBridgeIntegrationTest`.
 *
 * Every ordering is deterministic: the fake AgentOS never releases the observation by itself, the test
 * does, and only after the command under test has returned.
 */
class StopRunCancellationIntegrationTest : Neo4jDomainIntegrationTest() {

    @Autowired private lateinit var workflowService: WorkflowService
    @Autowired private lateinit var workflowRepository: WorkflowRepository
    @Autowired private lateinit var evidenceRepository: WorkflowEvidenceRepository
    @Autowired private lateinit var interactionRepository: HumanInteractionRepository
    @Autowired private lateinit var attemptRepository: AgentStepAttemptRepository
    @Autowired private lateinit var durableAgentAttemptService: DurableAgentAttemptService
    @Autowired private lateinit var sseHub: WorkflowSseHub
    @Autowired private lateinit var transactionManager: PlatformTransactionManager
    @Autowired private lateinit var submissions: SessionRunSubmissionService
    @Autowired private lateinit var outboxDrain: OutboxDrainService
    @Autowired private lateinit var outbox: SpringDataNeo4jOutboxRepository

    @TempDir
    lateinit var repoRoot: Path

    private val namespace = "2b6f8d2f-8d1a-4f0e-9b6e-stop-cancel"

    /** The user who launched every run of this class, i.e. the only one allowed to stop it. */
    private val actor = "engineer-stop"

    /**
     * Fake AgentOS boundary. The observation optionally blocks until the test releases it; the kill only
     * records the identity it carried and never releases anything itself.
     */
    private class StopAdapter(
        private val blockObservation: Boolean = true,
        private val verdict: AgentOsExecutionVerdict = AgentOsExecutionVerdict.Interrupted("User requested cancellation"),
    ) : AgentOsExecutionAdapter {
        val observing = CountDownLatch(1)
        val release = CountDownLatch(1)
        val createCalls = AtomicInteger()
        val startCalls = AtomicInteger()
        val interruptCalls = AtomicInteger()
        val interruptIdentities = CopyOnWriteArrayList<String?>()

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
            startCalls.incrementAndGet()
        }

        override fun observeTurn(caseId: String, attemptId: String, timeoutMs: Long): AgentOsExecutionVerdict {
            observing.countDown()
            if (blockObservation) release.await(30, TimeUnit.SECONDS)
            return verdict
        }

        override fun reconcile(caseId: String): AgentOsExecutionVerdict =
            AgentOsExecutionVerdict.Interrupted("User requested cancellation")

        override fun interrupt(caseId: String, reason: String) = Unit

        override fun interrupt(caseId: String, reason: String, externalUserId: String?): Boolean {
            interruptCalls.incrementAndGet()
            interruptIdentities.add(externalUserId)
            return true
        }

        override fun kill(caseId: String) = Unit
    }

    private fun stepJson(id: String, dependsOn: List<String>): Map<String, Any?> = linkedMapOf(
        "id" to id,
        "name" to "Step $id",
        "responsibility" to mapOf("kind" to "agent", "name" to "architect"),
        "dependsOn" to dependsOn,
    )

    private fun startWorkflow(workflowType: String, workflowId: String) {
        val raw = linkedMapOf<String, Any?>(
            "schemaVersion" to "1",
            "workflowType" to workflowType,
            "version" to "1.0.0",
            "title" to "Session $workflowType",
            "steps" to listOf(stepJson("A", emptyList()), stepJson("B", listOf("A"))),
        )
        val valid = WorkflowDefinitionValidator.validate(raw) as WorkflowDefinitionValidation.Valid
        workflowService.registerDefinition(
            scope,
            WorkflowDefinitionRecord(
                workflowType = workflowType,
                version = "1.0.0",
                definitionHash = hashWorkflowDefinition(valid.definition),
                definition = valid.definition,
            ),
        )
        workflowService.start(
            scope,
            namespace,
            WorkflowStartCommand(
                workflowId = workflowId,
                workflowType = workflowType,
                title = "Session $workflowType",
                controllerRequest = ControllerRequestInput("Do the thing", namespace, "2026-01-01T00:00:00Z", actor, "factory-cockpit"),
            ),
            ControllerExecutionInput(runtimeId = "test-runtime", kind = "agentos", agentId = "runner", namespaceId = namespace),
        )
    }

    private fun capabilityService(adapter: AgentOsExecutionAdapter) = CapabilityExecutionService(
        CapabilityResolver(),
        workflowRepository,
        evidenceRepository,
        interactionRepository,
        attemptRepository,
        agentStepResultService = null,
        transactionManager = transactionManager,
        durableAgentAttemptService = durableAgentAttemptService,
        agentOsExecutionAdapter = adapter,
    )

    private fun sessionRunner(
        adapter: AgentOsExecutionAdapter,
        precheck: WorkspacePrecheckService? = null,
    ): SessionRunService = SessionRunService(
        workflowRepository,
        evidenceRepository,
        interactionRepository,
        capabilityService(adapter),
        sseHub,
        oracleDefinitionRegistry = null,
        oracleExecutionService = null,
        transactionManager = transactionManager,
        workspacePrecheckService = precheck,
    )

    private fun cancellation(adapter: AgentOsExecutionAdapter) =
        BridgeCancellationService(durableAgentAttemptService, adapter, workflowRepository)

    private fun statusOf(workflowId: String, stepId: String): String =
        workflowRepository.findStepStates(scope, namespace, workflowId).first { it.stepId == stepId }.status

    private fun projectionStatus(workflowId: String): Any? =
        workflowRepository.findInstance(scope, namespace, workflowId)!!.projection["status"]

    private fun cancellationEvidence(workflowId: String) =
        evidenceRepository.list(scope, namespace, workflowId, "A")
            .filter { it.kind == "session-step" && it.outcome == WorkflowStatuses.CANCELLED }

    private fun stepAttempt(workflowId: String, attemptId: String, number: Int = 1, contextEnvelope: String? = null) =
        DurableAgentAttempt(
            attemptId = attemptId,
            caseId = CapabilityExecutionService.stableCaseId(workflowId, "A"),
            namespaceId = namespace,
            workflowId = workflowId,
            stepId = "A",
            attemptNumber = number,
            agentName = "architect",
            contextEnvelope = contextEnvelope,
        )

    /** The state a Stop left behind before the fix: step `running`, attempt cancelled, nobody observing. */
    private fun leaveStuckAfterStop(workflowId: String, adapter: AgentOsExecutionAdapter): String {
        val attemptId = CapabilityExecutionService.stableAttemptId(workflowId, "A")
        durableAgentAttemptService.register(scope, stepAttempt(workflowId, attemptId))
        durableAgentAttemptService.claim(scope, namespace, workflowId, "A", attemptId, "dead-worker", leaseTtlMs = 60_000)
        durableAgentAttemptService.transition(scope, namespace, workflowId, "A", attemptId, "dead-worker", AgentAttemptStatus.STARTING)
        durableAgentAttemptService.transition(scope, namespace, workflowId, "A", attemptId, "dead-worker", AgentAttemptStatus.RUNNING)
        workflowRepository.upsertStepState(
            scope,
            WorkflowStepStateRecord(namespace, workflowId, "A", 1, WorkflowStatuses.RUNNING, emptyMap()),
        )
        cancellation(adapter).requestCancel(scope, namespace, workflowId, attemptId, expectedRevision = null, actorId = actor)
        return attemptId
    }

    // ---- the two orderings of the race between the cancel command and the sequencer ----

    @Test
    fun `cancel first - Stop while the sequencer is observing seals the step and the run as cancelled`() {
        val workflowId = "wf-stop-live-${UUID.randomUUID()}"
        startWorkflow("stop-live", workflowId)
        val adapter = StopAdapter()
        val attemptId = CapabilityExecutionService.stableAttemptId(workflowId, "A")
        val pool = Executors.newSingleThreadExecutor()
        try {
            val running = pool.submit<SessionRunResult> {
                sessionRunner(adapter).runSession(scope, namespace, workflowId, repoRoot)
            }
            assertThat(adapter.observing.await(30, TimeUnit.SECONDS)).isTrue()

            val outcome = cancellation(adapter)
                .requestCancel(scope, namespace, workflowId, attemptId, expectedRevision = null, actorId = actor)
            // Only now, once the command returned, does AgentOS let the observation conclude (the killed
            // case): the sequencer therefore always meets an attempt that is already durably cancelled.
            adapter.release.countDown()

            val result = running.get(30, TimeUnit.SECONDS)
            assertThat(outcome.killAcknowledged).isTrue()
            // The kill is issued as the run owner.
            assertThat(adapter.interruptIdentities).containsExactly(actor)
            assertThat(result.status).isEqualTo(WorkflowStatuses.CANCELLED)
            assertThat(statusOf(workflowId, "A")).isEqualTo(WorkflowStatuses.CANCELLED)
            assertThat(statusOf(workflowId, "B")).isEqualTo(WorkflowStatuses.BLOCKED)
            assertThat(projectionStatus(workflowId)).isEqualTo(WorkflowStatuses.CANCELLED)
            val evidence = cancellationEvidence(workflowId)
            assertThat(evidence).hasSize(1)
            assertThat(evidence.single().facts["code"]).isEqualTo(CapabilityExecutionService.USER_CANCELLED)
            assertThat(evidence.single().facts["attemptId"]).isEqualTo(attemptId)
            // The attempt stays the user's cancellation: never rewritten as an ordinary interruption failure.
            val attempt = durableAgentAttemptService.find(scope, namespace, workflowId, "A", attemptId)!!
            assertThat(attempt.status).isEqualTo(AgentAttemptStatus.INTERRUPTED)
            assertThat(attempt.failureCode).isEqualTo(CapabilityExecutionService.USER_CANCELLED)
            assertThat(adapter.startCalls.get()).isEqualTo(1)
        } finally {
            pool.shutdownNow()
        }
    }

    @Test
    fun `sequencer first - the attempt already finalized keeps its verdict, the cancel is a 409 and nothing is killed`() {
        val workflowId = "wf-stop-late-${UUID.randomUUID()}"
        startWorkflow("stop-late", workflowId)
        val adapter = StopAdapter(
            blockObservation = false,
            verdict = AgentOsExecutionVerdict.Failed("AGENT_BROKE", "the agent failed on its own"),
        )
        val attemptId = CapabilityExecutionService.stableAttemptId(workflowId, "A")

        val result = sessionRunner(adapter).runSession(scope, namespace, workflowId, repoRoot)

        assertThat(result.status).isEqualTo(WorkflowStatuses.FAILED)
        assertThat(statusOf(workflowId, "A")).isEqualTo(WorkflowStatuses.FAILED)

        val refused = assertThrows(InvalidAttemptTransitionException::class.java) {
            cancellation(adapter).requestCancel(scope, namespace, workflowId, attemptId, expectedRevision = null, actorId = actor)
        }

        assertThat(refused.errorCode).isEqualTo("ATTEMPT_INVALID_TRANSITION")
        assertThat(adapter.interruptCalls.get()).isZero()
        assertThat(statusOf(workflowId, "A")).isEqualTo(WorkflowStatuses.FAILED)
        assertThat(statusOf(workflowId, "B")).isEqualTo(WorkflowStatuses.BLOCKED)
        assertThat(cancellationEvidence(workflowId)).isEmpty()
        val attempt = durableAgentAttemptService.find(scope, namespace, workflowId, "A", attemptId)!!
        assertThat(attempt.status).isEqualTo(AgentAttemptStatus.FAILED)
    }

    // ---- a run already stuck by a Stop, healed by the next continuation ----

    @Test
    fun `a stuck run without a live sequencer is sealed as cancelled by the next continuation and dispatches nothing`() {
        val workflowId = "wf-stop-stuck-${UUID.randomUUID()}"
        startWorkflow("stop-stuck", workflowId)
        val adapter = StopAdapter(blockObservation = false)
        leaveStuckAfterStop(workflowId, adapter)
        val runner = sessionRunner(adapter)

        val first = runner.runSession(scope, namespace, workflowId, repoRoot)

        assertThat(first.status).isEqualTo(WorkflowStatuses.CANCELLED)
        assertThat(statusOf(workflowId, "A")).isEqualTo(WorkflowStatuses.CANCELLED)
        assertThat(statusOf(workflowId, "B")).isEqualTo(WorkflowStatuses.BLOCKED)
        assertThat(projectionStatus(workflowId)).isEqualTo(WorkflowStatuses.CANCELLED)
        assertThat(cancellationEvidence(workflowId)).hasSize(1)
        assertThat(adapter.createCalls.get()).isZero()
        assertThat(adapter.startCalls.get()).isZero()

        // A later continuation changes nothing and still dispatches nothing.
        val evidenceCount = evidenceRepository.list(scope, namespace, workflowId).size
        val second = runner.runSession(scope, namespace, workflowId, repoRoot)

        assertThat(second.status).isEqualTo(WorkflowStatuses.CANCELLED)
        assertThat(statusOf(workflowId, "A")).isEqualTo(WorkflowStatuses.CANCELLED)
        assertThat(statusOf(workflowId, "B")).isEqualTo(WorkflowStatuses.BLOCKED)
        assertThat(evidenceRepository.list(scope, namespace, workflowId)).hasSize(evidenceCount)
        assertThat(adapter.createCalls.get()).isZero()
        assertThat(adapter.startCalls.get()).isZero()
    }

    @Test
    fun `a stuck run is still sealed as cancelled when the workspace precheck would fail`() {
        val workflowId = "wf-stop-precheck-${UUID.randomUUID()}"
        startWorkflow("stop-precheck", workflowId)
        val adapter = StopAdapter(blockObservation = false)
        leaveStuckAfterStop(workflowId, adapter)
        val precheck = mockk<WorkspacePrecheckService>()
        every { precheck.precheck(any(), any(), any(), any(), any()) } throws
            IllegalStateException("the workspace prerequisites are gone")

        val result = sessionRunner(adapter, precheck).runSession(scope, namespace, workflowId, repoRoot)

        assertThat(result.status).isEqualTo(WorkflowStatuses.CANCELLED)
        assertThat(statusOf(workflowId, "A")).isEqualTo(WorkflowStatuses.CANCELLED)
        assertThat(statusOf(workflowId, "B")).isEqualTo(WorkflowStatuses.BLOCKED)
        assertThat(projectionStatus(workflowId)).isEqualTo(WorkflowStatuses.CANCELLED)
        // The prelude runs before the precheck, and a sealed run never needs it.
        verify(exactly = 0) { precheck.precheck(any(), any(), any(), any(), any()) }
        assertThat(adapter.startCalls.get()).isZero()
    }

    // ---- the execution slot is freed and the durable continuation is drained ----

    @Test
    fun `the execution slot is freed after a Stop and the attempt-cancelled continuation is drained`() {
        val workflowId = "wf-stop-slot-${UUID.randomUUID()}"
        startWorkflow("stop-slot", workflowId)
        val adapter = StopAdapter(blockObservation = false)
        val attemptId = leaveStuckAfterStop(workflowId, adapter)
        val runner = sessionRunner(adapter)

        // The very continuation the controller enqueues once the cancel is committed.
        val submitted = submissions.submit(scope, namespace, workflowId, repoRoot.toString(), "attempt-cancelled:$attemptId", null)
        assertThat(submitted.queued).isTrue()
        OutboxDrainWorker(outboxDrain, runner, mockk<AgentOsProxyClient>(), ObjectMapper(), Clock.systemUTC()).drain()

        assertThat(statusOf(workflowId, "A")).isEqualTo(WorkflowStatuses.CANCELLED)
        assertThat(statusOf(workflowId, "B")).isEqualTo(WorkflowStatuses.BLOCKED)
        assertThat(projectionStatus(workflowId)).isEqualTo(WorkflowStatuses.CANCELLED)
        val continuation = outbox.findAllByOrganization(ORGANIZATION_ID).single { it.eventType == "session_continuation" }
        assertThat(continuation.status).isEqualTo("dispatched")

        // The per-workflow sequencer lock was released: another thread gets the slot at once, observes the
        // sealed run and dispatches nothing.
        val pool = Executors.newSingleThreadExecutor()
        try {
            val again = pool.submit<SessionRunResult> { runner.runSession(scope, namespace, workflowId, repoRoot) }
                .get(15, TimeUnit.SECONDS)
            assertThat(again.status).isEqualTo(WorkflowStatuses.CANCELLED)
        } finally {
            pool.shutdownNow()
        }
        assertThat(adapter.createCalls.get()).isZero()
        assertThat(adapter.startCalls.get()).isZero()
    }

    // ---- a legitimate re-arm is never re-sealed by the cancellation of a previous attempt ----

    @Test
    fun `a re-armed READY step whose latest attempt is still the old cancellation is not sealed as cancelled`() {
        val workflowId = "wf-stop-rearm-ready-${UUID.randomUUID()}"
        startWorkflow("stop-rearm-ready", workflowId)
        val adapter = StopAdapter(blockObservation = false)
        leaveStuckAfterStop(workflowId, adapter)
        // A retry re-arms the step: it waits for a new attempt, `ready`.
        workflowRepository.upsertStepState(
            scope,
            WorkflowStepStateRecord(namespace, workflowId, "A", 1, WorkflowStatuses.READY, emptyMap()),
        )

        val result = sessionRunner(adapter).runSession(scope, namespace, workflowId, repoRoot)

        assertThat(statusOf(workflowId, "A")).isNotEqualTo(WorkflowStatuses.CANCELLED)
        assertThat(result.status).isNotEqualTo(WorkflowStatuses.CANCELLED)
        assertThat(cancellationEvidence(workflowId)).isEmpty()
    }

    @Test
    fun `a re-armed step with a successor attempt is dispatched and not sealed as cancelled`() {
        val workflowId = "wf-stop-rearm-successor-${UUID.randomUUID()}"
        startWorkflow("stop-rearm-successor", workflowId)
        val adapter = StopAdapter(
            blockObservation = false,
            verdict = AgentOsExecutionVerdict.Failed("AGENT_BROKE", "the retried agent failed on its own"),
        )
        leaveStuckAfterStop(workflowId, adapter)
        durableAgentAttemptService.registerRetry(
            scope,
            stepAttempt(workflowId, CapabilityExecutionService.retryAttemptId(workflowId, "A", 2), number = 2),
        )
        workflowRepository.upsertStepState(
            scope,
            WorkflowStepStateRecord(namespace, workflowId, "A", 1, WorkflowStatuses.READY, emptyMap()),
        )

        sessionRunner(adapter).runSession(scope, namespace, workflowId, repoRoot)

        // The retried attempt really ran: its own verdict decides the step, never the old cancellation.
        assertThat(adapter.startCalls.get()).isEqualTo(1)
        assertThat(statusOf(workflowId, "A")).isEqualTo(WorkflowStatuses.FAILED)
        assertThat(cancellationEvidence(workflowId)).isEmpty()
    }

    @Test
    fun `the cancellation guard is bound to the attempt of the current execution of the step`() {
        val workflowId = "wf-stop-execution-${UUID.randomUUID()}"
        startWorkflow("stop-execution", workflowId)
        val adapter = StopAdapter(blockObservation = false)
        val capability = capabilityService(adapter)
        val attemptId = CapabilityExecutionService.stableAttemptId(workflowId, "A")
        val envelope = AttemptContextEnvelope(
            organizationId = scope.organizationId,
            workstreamId = scope.workstreamId,
            namespaceId = namespace,
            workflowId = workflowId,
            stepId = "A",
            attemptNumber = 1,
            agentName = "architect",
            briefHash = "sha256:brief",
            inputs = mapOf("stepExecutionId" to "execution-1"),
        ).toJson()
        durableAgentAttemptService.register(scope, stepAttempt(workflowId, attemptId, contextEnvelope = envelope))
        durableAgentAttemptService.claim(scope, namespace, workflowId, "A", attemptId, "worker", leaseTtlMs = 60_000)
        durableAgentAttemptService.transition(scope, namespace, workflowId, "A", attemptId, "worker", AgentAttemptStatus.STARTING)
        durableAgentAttemptService.transition(scope, namespace, workflowId, "A", attemptId, "worker", AgentAttemptStatus.RUNNING)
        // Not cancelled yet: nothing to seal.
        assertThat(capability.userCancelledAttemptId(scope, namespace, workflowId, "A")).isNull()
        durableAgentAttemptService.requestCancel(scope, namespace, workflowId, "A", attemptId)

        // The step state carries no execution (legacy run): the cancelled latest attempt is the one to seal.
        assertThat(capability.userCancelledAttemptId(scope, namespace, workflowId, "A")).isEqualTo(attemptId)

        // The step state carries the execution the attempt is pinned to: still the one to seal.
        workflowRepository.upsertStepState(
            scope,
            WorkflowStepStateRecord(
                namespace, workflowId, "A", 1, WorkflowStatuses.RUNNING,
                mapOf("execution" to mapOf("executionId" to "execution-1")),
            ),
        )
        assertThat(capability.userCancelledAttemptId(scope, namespace, workflowId, "A")).isEqualTo(attemptId)

        // The step moved on to another execution (a correction cycle): the old cancellation no longer applies.
        workflowRepository.upsertStepState(
            scope,
            WorkflowStepStateRecord(
                namespace, workflowId, "A", 1, WorkflowStatuses.RUNNING,
                mapOf("execution" to mapOf("executionId" to "execution-2")),
            ),
        )
        assertThat(capability.userCancelledAttemptId(scope, namespace, workflowId, "A")).isNull()
    }
}
