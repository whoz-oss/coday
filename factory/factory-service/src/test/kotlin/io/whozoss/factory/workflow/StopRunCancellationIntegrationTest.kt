package io.whozoss.factory.workflow

import io.whozoss.factory.Neo4jDomainIntegrationTest
import io.whozoss.factory.adapter.agentos.AgentOsExecutionAdapter
import io.whozoss.factory.adapter.agentos.AgentOsExecutionVerdict
import io.whozoss.factory.adapter.agentos.CaseHandle
import io.whozoss.factory.agentattempt.domain.AgentAttemptStatus
import io.whozoss.factory.agentattempt.domain.DurableAgentAttempt
import io.whozoss.factory.agentattempt.persistence.AgentStepAttemptRepository
import io.whozoss.factory.agentattempt.service.BridgeCancellationService
import io.whozoss.factory.agentattempt.service.DurableAgentAttemptService
import io.whozoss.factory.capability.CapabilityExecutionService
import io.whozoss.factory.capability.CapabilityResolver
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
import io.whozoss.factory.workflow.service.WorkflowService
import io.whozoss.factory.workflow.sse.WorkflowSseHub
import java.nio.file.Path
import java.util.UUID
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.CountDownLatch
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.transaction.PlatformTransactionManager

/**
 * Non-regression of WZ-36379: a user Stop must seal the step AND the run as `cancelled`.
 *
 * Before the fix the cancel rotated the lease owner, the sequencer's finalization was fenced and the
 * step stayed `running` forever (so did the run); with no live sequencer nothing ever replayed the
 * cancelled attempt, and a replay would have classified it as a failure. The real Spring beans and a
 * stateful fake of the AgentOS boundary are used, as in `DurableAgentOsBridgeIntegrationTest`.
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

    @TempDir
    lateinit var repoRoot: Path

    private val namespace = "2b6f8d2f-8d1a-4f0e-9b6e-stop-cancel"
    private val actor = "engineer-stop"

    /** Fake AgentOS boundary: the observation blocks until the (authenticated) kill releases it. */
    private class StopAdapter : AgentOsExecutionAdapter {
        val observing = CountDownLatch(1)
        val killed = CountDownLatch(1)
        val createCalls = AtomicInteger()
        val startCalls = AtomicInteger()
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
            killed.await(30, TimeUnit.SECONDS)
            return AgentOsExecutionVerdict.Interrupted("User requested cancellation")
        }

        override fun reconcile(caseId: String): AgentOsExecutionVerdict =
            AgentOsExecutionVerdict.Interrupted("User requested cancellation")

        override fun interrupt(caseId: String, reason: String) = Unit

        override fun interrupt(caseId: String, reason: String, externalUserId: String?): Boolean {
            interruptIdentities.add(externalUserId)
            killed.countDown()
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

    private fun sessionRunner(adapter: AgentOsExecutionAdapter): SessionRunService = SessionRunService(
        workflowRepository,
        evidenceRepository,
        interactionRepository,
        CapabilityExecutionService(
            CapabilityResolver(),
            workflowRepository,
            evidenceRepository,
            interactionRepository,
            attemptRepository,
            agentStepResultService = null,
            transactionManager = transactionManager,
            durableAgentAttemptService = durableAgentAttemptService,
            agentOsExecutionAdapter = adapter,
        ),
        sseHub,
        oracleDefinitionRegistry = null,
        oracleExecutionService = null,
        transactionManager = transactionManager,
    )

    private fun statusOf(workflowId: String, stepId: String): String =
        workflowRepository.findStepStates(scope, namespace, workflowId).first { it.stepId == stepId }.status

    private fun projectionStatus(workflowId: String): Any? =
        workflowRepository.findInstance(scope, namespace, workflowId)!!.projection["status"]

    private fun cancellationEvidence(workflowId: String) =
        evidenceRepository.list(scope, namespace, workflowId, "A")
            .filter { it.kind == "session-step" && it.outcome == WorkflowStatuses.CANCELLED }

    @Test
    fun `Stop while the sequencer is observing seals the step and the run as cancelled`() {
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

            val outcome = BridgeCancellationService(durableAgentAttemptService, adapter, workflowRepository)
                .requestCancel(scope, namespace, workflowId, attemptId, expectedRevision = null, actorId = "caller-user")

            val result = running.get(30, TimeUnit.SECONDS)
            assertThat(outcome.killAcknowledged).isTrue()
            // The persisted workflow actor, never the caller, is the identity of the kill.
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
    fun `a stuck run without a live sequencer is sealed as cancelled by the next continuation and dispatches nothing`() {
        val workflowId = "wf-stop-stuck-${UUID.randomUUID()}"
        startWorkflow("stop-stuck", workflowId)
        val attemptId = CapabilityExecutionService.stableAttemptId(workflowId, "A")
        // The state a Stop left behind before the fix: step running, attempt cancelled, nobody observing.
        durableAgentAttemptService.register(
            scope,
            DurableAgentAttempt(
                attemptId = attemptId,
                caseId = CapabilityExecutionService.stableCaseId(workflowId, "A"),
                namespaceId = namespace,
                workflowId = workflowId,
                stepId = "A",
                attemptNumber = 1,
                agentName = "architect",
            ),
        )
        durableAgentAttemptService.claim(scope, namespace, workflowId, "A", attemptId, "dead-worker", leaseTtlMs = 60_000)
        durableAgentAttemptService.transition(scope, namespace, workflowId, "A", attemptId, "dead-worker", AgentAttemptStatus.STARTING)
        durableAgentAttemptService.transition(scope, namespace, workflowId, "A", attemptId, "dead-worker", AgentAttemptStatus.RUNNING)
        workflowRepository.upsertStepState(
            scope,
            WorkflowStepStateRecord(namespace, workflowId, "A", 1, WorkflowStatuses.RUNNING, emptyMap()),
        )
        val adapter = StopAdapter()
        BridgeCancellationService(durableAgentAttemptService, adapter, workflowRepository)
            .requestCancel(scope, namespace, workflowId, attemptId, expectedRevision = null)
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
}
