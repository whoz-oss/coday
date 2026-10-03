package io.whozoss.factory.workflow

import io.whozoss.factory.Neo4jDomainIntegrationTest
import io.whozoss.factory.adapter.agentos.AgentOsExecutionAdapter
import io.whozoss.factory.adapter.agentos.AgentOsExecutionVerdict
import io.whozoss.factory.adapter.agentos.CaseHandle
import io.whozoss.factory.agentattempt.domain.AgentAttemptStatus
import io.whozoss.factory.agentattempt.domain.DurableAgentAttempt
import io.whozoss.factory.agentattempt.domain.IdempotencyKeyCollisionException
import io.whozoss.factory.agentattempt.persistence.AgentStepAttemptRepository
import io.whozoss.factory.agentattempt.service.BridgeRecoveryWorker
import io.whozoss.factory.agentattempt.service.DurableAgentAttemptService
import io.whozoss.factory.capability.CapabilityExecutionService
import io.whozoss.factory.capability.CapabilityResolver
import io.whozoss.factory.workflow.domain.ControllerExecutionInput
import io.whozoss.factory.workflow.domain.ControllerRequestInput
import io.whozoss.factory.workflow.domain.ResponsibilityKind
import io.whozoss.factory.workflow.domain.WorkflowDefinitionRecord
import io.whozoss.factory.workflow.domain.WorkflowDefinitionValidation
import io.whozoss.factory.workflow.domain.WorkflowDefinitionValidator
import io.whozoss.factory.workflow.domain.WorkflowStartCommand
import io.whozoss.factory.workflow.domain.WorkflowStatuses
import io.whozoss.factory.workflow.domain.WorkflowStepDefinition
import io.whozoss.factory.workflow.domain.WorkflowStepResponsibility
import io.whozoss.factory.workflow.domain.hashWorkflowDefinition
import io.whozoss.factory.workflow.persistence.HumanInteractionRepository
import io.whozoss.factory.workflow.persistence.WorkflowEvidenceRepository
import io.whozoss.factory.workflow.persistence.WorkflowRepository
import io.whozoss.factory.workflow.service.SessionRunService
import io.whozoss.factory.workflow.service.WorkflowService
import io.whozoss.factory.workflow.sse.WorkflowSseHub
import io.mockk.every
import io.mockk.mockk
import java.nio.file.Path
import java.util.UUID
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.CountDownLatch
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicInteger
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Assertions.assertThrows
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.transaction.PlatformTransactionManager
import org.springframework.transaction.support.TransactionSynchronizationManager

/**
 * Integration tests of the durable AgentOS-Factory bridge (Lot C / étapes 3, 6, 7).
 *
 * The bridge is exercised through the REAL Spring beans
 * ([DurableAgentAttemptService], [WorkflowRepository], evidence/interaction
 * repositories, the autowired [PlatformTransactionManager]) and a stateful fake
 * [AgentOsExecutionAdapter], so the whole orchestration — short claim transaction,
 * untransacted remote turn, short finalize transaction — runs exactly as in
 * production.
 *
 * Acceptance criteria validated:
 *  - A blocks/fails/waiting-human ⇒ B never starts;
 *  - A succeeds with an identifiable output ⇒ B starts once, receiving exactly
 *    that durable output (never the last free message);
 *  - two concurrent executions ⇒ one single attempt owns the execution;
 *  - a crash after case creation (before local persistence) recovers and reattaches
 *    the case;
 *  - a crash after the message was accepted (before the HTTP response) never sends
 *    a second turn (idempotence by attemptId);
 *  - a turn longer than the Neo4j transaction timeout runs with no open transaction.
 */
class DurableAgentOsBridgeIntegrationTest : Neo4jDomainIntegrationTest() {

    @Autowired
    private lateinit var workflowService: WorkflowService

    @Autowired
    private lateinit var workflowRepository: WorkflowRepository

    @Autowired
    private lateinit var evidenceRepository: WorkflowEvidenceRepository

    @Autowired
    private lateinit var interactionRepository: HumanInteractionRepository

    @Autowired
    private lateinit var attemptRepository: AgentStepAttemptRepository

    @Autowired
    private lateinit var durableAgentAttemptService: DurableAgentAttemptService

    @Autowired
    private lateinit var sseHub: WorkflowSseHub

    @Autowired
    private lateinit var transactionManager: PlatformTransactionManager

    @TempDir
    lateinit var repoRoot: Path

    private val namespace = "2b6f8d2f-8d1a-4f0e-9b6e-agentos-bridge"

    private fun isolatedWorkflowId(base: String): String = "$base-${UUID.randomUUID()}"

    private fun caseSteps(workflowId: String, vararg stepIds: String): Map<String, String> =
        stepIds.associateBy { CapabilityExecutionService.stableCaseId(workflowId, it) }

    // ----- fixtures ------------------------------------------------------

    private fun stepJson(id: String, kind: String, name: String, dependsOn: List<String>): Map<String, Any?> =
        linkedMapOf(
            "id" to id,
            "name" to "Step $id",
            "responsibility" to mapOf("kind" to kind, "name" to name),
            "dependsOn" to dependsOn,
        )

    private fun startSession(workflowType: String, workflowId: String, steps: List<Map<String, Any?>>) {
        val raw = linkedMapOf<String, Any?>(
            "schemaVersion" to "1",
            "workflowType" to workflowType,
            "version" to "1.0.0",
            "title" to "Session $workflowType",
            "steps" to steps,
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
            WorkflowStartCommand(workflowId = workflowId, workflowType = workflowType, title = "Session $workflowType"),
            ControllerExecutionInput(runtimeId = "test-runtime", kind = "agentos", agentId = "runner", namespaceId = namespace),
        )
    }

    private fun statusOf(workflowId: String, stepId: String): String =
        workflowRepository.findStepStates(scope, namespace, workflowId).first { it.stepId == stepId }.status

    @Suppress("UNCHECKED_CAST")
    private fun projectedStep(workflowId: String, stepId: String): Map<String, Any?> {
        val projection = workflowService.getProjection(scope, namespace, workflowId)["projection"] as Map<String, Any?>
        return (projection["steps"] as List<Map<String, Any?>>).first { it["id"] == stepId }
    }

    private fun agentStep(id: String, dependsOn: List<String> = emptyList()): WorkflowStepDefinition =
        WorkflowStepDefinition(id, "Step $id", WorkflowStepResponsibility(ResponsibilityKind.AGENT, "architect"), dependsOn)

    private fun bridgeService(adapter: AgentOsExecutionAdapter, leaseTtlMs: Long? = 3_600_000L): CapabilityExecutionService =
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
            agentLeaseTtlMs = leaseTtlMs,
        )

    private fun sessionRunner(adapter: AgentOsExecutionAdapter, leaseTtlMs: Long? = 3_600_000L): SessionRunService =
        SessionRunService(
            workflowRepository,
            evidenceRepository,
            interactionRepository,
            bridgeService(adapter, leaseTtlMs),
            sseHub,
            oracleDefinitionRegistry = null,
            oracleExecutionService = null,
            transactionManager = transactionManager,
        )

    // ----- fake adapter --------------------------------------------------

    private data class StartTurn(val caseId: String, val persona: String, val brief: String, val attemptId: String)

    /**
     * Stateful fake of the AgentOS execution boundary. It records every start
     * turn, remembers the cases it created (so a re-run recovers them), and
     * resolves each deterministic case id through the mapping supplied by the test.
     */
    private open inner class FakeAdapter(
        private val stepByCaseId: Map<String, String>,
        private val verdictFor: (String) -> AgentOsExecutionVerdict,
    ) : AgentOsExecutionAdapter {
        val startTurns = CopyOnWriteArrayList<StartTurn>()
        val createCalls = AtomicInteger()
        val observeCalls = AtomicInteger()
        val reconcileCalls = AtomicInteger()
        val startedInTransaction = AtomicBoolean(false)
        val observedInTransaction = AtomicBoolean(false)
        private val knownCases = ConcurrentHashMap.newKeySet<String>()
        private val recovered = ConcurrentHashMap.newKeySet<String>()

        /** Pre-registers a case as if it had been created by a crashed prior run. */
        fun preSeedCase(caseId: String) {
            knownCases.add(caseId)
        }

        protected fun stepOf(caseId: String): String =
            requireNotNull(stepByCaseId[caseId]) { "Unknown AgentOS case id: $caseId" }

        override fun createOrRecoverExecution(
            namespaceId: String,
            workflowId: String,
            stepId: String,
            externalUserId: String?,
            attemptId: String,
            capabilityToken: String?,
            caseId: String,
        ): CaseHandle {
            createCalls.incrementAndGet()
            val alreadyKnown = !knownCases.add(caseId)
            if (alreadyKnown) recovered.add(caseId)
            return CaseHandle(caseId = caseId, namespaceId = namespaceId, recovered = alreadyKnown)
        }

        open override fun startTurn(
            caseId: String,
            persona: String,
            brief: String,
            externalUserId: String?,
            attemptId: String,
            capabilityToken: String?,
        ) {
            if (TransactionSynchronizationManager.isActualTransactionActive()) startedInTransaction.set(true)
            startTurns.add(StartTurn(caseId, persona, brief, attemptId))
        }

        override fun observeTurn(caseId: String, attemptId: String, timeoutMs: Long): AgentOsExecutionVerdict {
            observeCalls.incrementAndGet()
            if (TransactionSynchronizationManager.isActualTransactionActive()) observedInTransaction.set(true)
            return verdictFor(stepOf(caseId))
        }

        override fun observeTurn(
            caseId: String,
            attemptId: String,
            timeoutMs: Long,
            onIntermediateVerdict: (AgentOsExecutionVerdict.WaitingHuman) -> Unit,
        ): AgentOsExecutionVerdict {
            val verdict = observeTurn(caseId, attemptId, timeoutMs)
            if (verdict is AgentOsExecutionVerdict.WaitingHuman) onIntermediateVerdict(verdict)
            return verdict
        }

        override fun reconcile(caseId: String): AgentOsExecutionVerdict {
            reconcileCalls.incrementAndGet()
            return verdictFor(stepOf(caseId))
        }

        override fun interrupt(caseId: String, reason: String) = Unit

        override fun kill(caseId: String) = Unit
    }

    // ----- 1. A fails ⇒ B never starts ----------------------------------

    @Test
    fun `a failed agent step blocks B and B never starts`() {
        val workflowId = isolatedWorkflowId("wf-bridge-fail")
        startSession(
            "bridge-fail",
            workflowId,
            listOf(stepJson("A", "agent", "architect", emptyList()), stepJson("B", "agent", "architect", listOf("A"))),
        )
        val caseB = CapabilityExecutionService.stableCaseId(workflowId, "B")
        val adapter = FakeAdapter(caseSteps(workflowId, "A", "B")) { stepId ->
            if (stepId == "A") AgentOsExecutionVerdict.Failed("AGENT_CASE_ERROR", "boom")
            else AgentOsExecutionVerdict.Succeeded(emptyMap())
        }

        val result = sessionRunner(adapter).runSession(scope, namespace, workflowId, repoRoot)

        assertThat(result.status).isEqualTo(WorkflowStatuses.FAILED)
        assertThat(statusOf(workflowId, "A")).isEqualTo(WorkflowStatuses.FAILED)
        assertThat(statusOf(workflowId, "B")).isEqualTo(WorkflowStatuses.BLOCKED)
        assertThat(adapter.startTurns.map { it.caseId }).doesNotContain(caseB)
    }

    // ----- 2. A waits human ⇒ B never starts -----------------------------

    @Test
    fun `an agent step waiting for a human suspends the session and B never starts`() {
        val workflowId = isolatedWorkflowId("wf-bridge-human")
        startSession(
            "bridge-human",
            workflowId,
            listOf(stepJson("A", "agent", "architect", emptyList()), stepJson("B", "agent", "architect", listOf("A"))),
        )
        val caseB = CapabilityExecutionService.stableCaseId(workflowId, "B")
        val adapter = FakeAdapter(caseSteps(workflowId, "A", "B")) { stepId ->
            if (stepId == "A") AgentOsExecutionVerdict.WaitingHuman("question-1", "Which port?")
            else AgentOsExecutionVerdict.Succeeded(emptyMap())
        }

        val result = sessionRunner(adapter).runSession(scope, namespace, workflowId, repoRoot)

        assertThat(result.status).isEqualTo(WorkflowStatuses.WAITING_HUMAN)
        assertThat(statusOf(workflowId, "A")).isEqualTo(WorkflowStatuses.WAITING_HUMAN)
        assertThat(statusOf(workflowId, "B")).isEqualTo(WorkflowStatuses.PENDING)
        assertThat(adapter.startTurns.map { it.caseId }).doesNotContain(caseB)
    }

    // ----- 3. A succeeds with an identifiable output ⇒ B receives it -----

    @Test
    fun `a successful agent step releases B which receives exactly A's persisted output`() {
        val workflowId = isolatedWorkflowId("wf-bridge-success")
        startSession(
            "bridge-success",
            workflowId,
            listOf(stepJson("A", "agent", "architect", emptyList()), stepJson("B", "agent", "architect", listOf("A"))),
        )
        val outputs = mapOf("artifactHash" to "sha256:1234", "summary" to "Build OK")
        val caseB = CapabilityExecutionService.stableCaseId(workflowId, "B")
        val adapter = object : FakeAdapter(caseSteps(workflowId, "A", "B"), { stepId ->
            if (stepId == "A") AgentOsExecutionVerdict.Succeeded(outputs) else AgentOsExecutionVerdict.Succeeded(emptyMap())
        }) {
            var projectedRunningAtRemoteStart = false
            override fun startTurn(
                caseId: String,
                persona: String,
                brief: String,
                externalUserId: String?,
                attemptId: String,
                capabilityToken: String?,
            ) {
                projectedRunningAtRemoteStart = projectedStep(workflowId, stepOf(caseId))["status"] == WorkflowStatuses.RUNNING
                super.startTurn(caseId, persona, brief, externalUserId, attemptId, capabilityToken)
            }
        }

        val result = sessionRunner(adapter).runSession(scope, namespace, workflowId, repoRoot)

        assertThat(result.status).isEqualTo(WorkflowStatuses.COMPLETED)
        assertThat(statusOf(workflowId, "A")).isEqualTo(WorkflowStatuses.COMPLETED)
        assertThat(statusOf(workflowId, "B")).isEqualTo(WorkflowStatuses.COMPLETED)

        // A's structured outputs are durably persisted as `agent-result` evidence.
        val aResult = evidenceRepository.list(scope, namespace, workflowId, "A")
            .single { it.kind == "agent-result" }
        assertThat(aResult.outcome).isEqualTo("pass")
        assertThat(aResult.facts["outputs"]).isEqualTo(outputs)

        // B started exactly once and its brief was built from A's PERSISTED outputs.
        val bTurns = adapter.startTurns.filter { it.caseId == caseB }
        assertThat(bTurns).hasSize(1)
        assertThat(bTurns.single().brief)
            .contains("sha256:1234")
            .contains("Build OK")
        assertThat(adapter.startedInTransaction.get()).isFalse()
        assertThat(adapter.observedInTransaction.get()).isFalse()
        assertThat(adapter.projectedRunningAtRemoteStart).isTrue()
    }

    @Test
    fun `governed agent step persists and sends the exact composed controller request brief`() {
        val workflowId = isolatedWorkflowId("wf-bridge-controller-request")
        val requestText = "Implement the persisted engineer request without widening scope."
        val raw = linkedMapOf<String, Any?>(
            "schemaVersion" to "1",
            "workflowType" to "bridge-controller-request",
            "version" to "1.0.0",
            "title" to "Controller request brief",
            "steps" to listOf(stepJson("A", "agent", "architect", emptyList())),
        )
        val valid = WorkflowDefinitionValidator.validate(raw) as WorkflowDefinitionValidation.Valid
        workflowService.registerDefinition(
            scope,
            WorkflowDefinitionRecord(
                workflowType = "bridge-controller-request",
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
                workflowType = "bridge-controller-request",
                title = "Controller request brief",
                controllerRequest = ControllerRequestInput(requestText, namespace, "2026-01-01T00:00:00Z", "engineer", "factory-cockpit"),
            ),
            ControllerExecutionInput(runtimeId = "test-runtime", kind = "agentos", agentId = "runner", namespaceId = namespace),
        )
        val adapter = FakeAdapter(caseSteps(workflowId, "A")) { AgentOsExecutionVerdict.Succeeded(emptyMap()) }

        val result = sessionRunner(adapter).runSession(scope, namespace, workflowId, repoRoot)

        assertThat(result.status).isEqualTo(WorkflowStatuses.COMPLETED)
        val brief = adapter.startTurns.single().brief
        assertThat(brief).isEqualTo(
            """## Global user request
$requestText

## Current step
Name: Step A
Instructions: Execute Factory session step 'A'.

## Scope and structured result
Work only on this step. Return a structured result for downstream Factory steps.""",
        )
        val attempt = durableAgentAttemptService.find(
            scope, namespace, workflowId, "A", CapabilityExecutionService.stableAttemptId(workflowId, "A"),
        )
        assertThat(attempt!!.brief).isEqualTo(brief)
        assertThat(brief.split(requestText)).hasSize(2)
    }

    // ----- 3b. Reservation BEFORE case creation; caseId BEFORE the turn ----

    /**
     * Reqs 3 & 4 attestation: the durable attempt is idempotently reserved
     * BEFORE the AgentOS case is created, and the `caseId` is persisted on the
     * attempt BEFORE the useful work (the turn brief) is dispatched.
     */
    @Test
    fun `the attempt is reserved before case creation and the caseId is persisted before the turn is sent`() {
        val workflowId = isolatedWorkflowId("wf-bridge-reserve-order")
        startSession("bridge-reserve-order", workflowId, listOf(stepJson("A", "agent", "architect", emptyList())))
        val attemptId = CapabilityExecutionService.stableAttemptId(workflowId, "A")
        val expectedCaseId = CapabilityExecutionService.stableCaseId(workflowId, "A")
        var statusAtCaseCreation: AgentAttemptStatus? = null
        var statusAtStartTurn: AgentAttemptStatus? = null
        var caseIdAtStartTurn: String? = null
        val adapter = object : FakeAdapter(caseSteps(workflowId, "A"), { AgentOsExecutionVerdict.Succeeded(emptyMap()) }) {
            override fun createOrRecoverExecution(
                namespaceId: String,
                workflowId: String,
                stepId: String,
                externalUserId: String?,
                attemptId: String,
                capabilityToken: String?,
                caseId: String,
            ): CaseHandle {
                // Req 3: at the very first AgentOS case creation, the durable
                // attempt already exists AND is reserved (claimed), not pending.
                statusAtCaseCreation = durableAgentAttemptService.find(
                    scope, namespace, workflowId, "A", CapabilityExecutionService.stableAttemptId(workflowId, "A"),
                )?.status
                return super.createOrRecoverExecution(namespaceId, workflowId, stepId, externalUserId, attemptId, capabilityToken, caseId)
            }

            override fun startTurn(
                caseId: String,
                persona: String,
                brief: String,
                externalUserId: String?,
                attemptId: String,
                capabilityToken: String?,
            ) {
                // Req 4: at the instant the useful work is dispatched, the
                // deterministic caseId is already persisted on the attempt and
                // the attempt is durably marked `starting`.
                val attempt = durableAgentAttemptService.find(
                    scope, namespace, workflowId, "A", CapabilityExecutionService.stableAttemptId(workflowId, "A"),
                )
                statusAtStartTurn = attempt?.status
                caseIdAtStartTurn = attempt?.caseId
                super.startTurn(caseId, persona, brief, externalUserId, attemptId, capabilityToken)
            }
        }

        val result = sessionRunner(adapter).runSession(scope, namespace, workflowId, repoRoot)

        assertThat(result.status).isEqualTo(WorkflowStatuses.COMPLETED)
        assertThat(adapter.createCalls.get()).isEqualTo(1)
        assertThat(adapter.startTurns).hasSize(1)
        assertThat(statusAtCaseCreation).isEqualTo(AgentAttemptStatus.CLAIMING)
        assertThat(statusAtStartTurn).isEqualTo(AgentAttemptStatus.STARTING)
        assertThat(caseIdAtStartTurn).isEqualTo(expectedCaseId)
        val attempt = durableAgentAttemptService.find(scope, namespace, workflowId, "A", attemptId)
        assertThat(attempt!!.caseId).isEqualTo(expectedCaseId)
    }

    // ----- 4. Two concurrent executions ⇒ one attempt owns it ------------

    @Test
    fun `two concurrent executions yield exactly one owning attempt and one turn`() {
        val workflowId = isolatedWorkflowId("wf-bridge-concurrency")
        startSession("bridge-concurrency", workflowId, listOf(stepJson("A", "agent", "architect", emptyList())))
        val adapter = FakeAdapter(caseSteps(workflowId, "A")) { AgentOsExecutionVerdict.Succeeded(emptyMap()) }
        val service = bridgeService(adapter)
        val step = agentStep("A")
        val gate = CountDownLatch(1)
        val pool = Executors.newFixedThreadPool(2)
        try {
            val futures = (1..2).map {
                pool.submit<Any> {
                    gate.await()
                    service.resolveAndRecord(scope, namespace, workflowId, step, repoRoot)
                }
            }
            gate.countDown()
            futures.forEach { it.get(30, TimeUnit.SECONDS) }
        } finally {
            pool.shutdownNow()
        }

        // The atomic durable claim lets exactly one attempt own the execution.
        assertThat(adapter.startTurns).hasSize(1)
        assertThat(adapter.observeCalls.get()).isEqualTo(1)
    }

    // ----- 5. Crash after case creation ⇒ case recovered and reattached ---

    @Test
    fun `a case created before local persistence is recovered and reattached on replay`() {
        val workflowId = isolatedWorkflowId("wf-bridge-recovery")
        startSession("bridge-recovery", workflowId, listOf(stepJson("A", "agent", "architect", emptyList())))
        val caseId = CapabilityExecutionService.stableCaseId(workflowId, "A")
        val adapter = FakeAdapter(caseSteps(workflowId, "A")) { AgentOsExecutionVerdict.Succeeded(mapOf("summary" to "recovered")) }
        // The AgentOS case already exists (created by the crashed prior run).
        adapter.preSeedCase(caseId)

        val result = sessionRunner(adapter).runSession(scope, namespace, workflowId, repoRoot)

        assertThat(result.status).isEqualTo(WorkflowStatuses.COMPLETED)
        // The very same case was recovered (no duplicate creation), and the turn ran on it.
        assertThat(adapter.startTurns.map { it.caseId }).containsExactly(caseId)
        assertThat(adapter.startTurns.single().attemptId)
            .isEqualTo(CapabilityExecutionService.stableAttemptId(workflowId, "A"))
        val attempt = durableAgentAttemptService.find(
            scope, namespace, workflowId, "A", CapabilityExecutionService.stableAttemptId(workflowId, "A"),
        )
        assertThat(attempt!!.status).isEqualTo(AgentAttemptStatus.SUCCEEDED)
    }

    // ----- 6. Crash after message accepted ⇒ no second turn --------------

    @Test
    fun `a crash after the message was accepted never sends a second turn`() {
        val workflowId = isolatedWorkflowId("wf-bridge-idempotence")
        startSession("bridge-idempotence", workflowId, listOf(stepJson("A", "agent", "architect", emptyList())))
        val attemptId = CapabilityExecutionService.stableAttemptId(workflowId, "A")
        val caseId = CapabilityExecutionService.stableCaseId(workflowId, "A")
        // Simulate the crashed prior run: the attempt was claimed and the turn was
        // marked `starting` (i.e. the message had been accepted) but nothing was
        // finalized. The lease has expired (ttl = 0).
        durableAgentAttemptService.register(
            scope,
            DurableAgentAttempt(
                attemptId = attemptId,
                caseId = caseId,
                namespaceId = namespace,
                workflowId = workflowId,
                stepId = "A",
                attemptNumber = 1,
                agentName = "architect",
            ),
        )
        durableAgentAttemptService.claim(scope, namespace, workflowId, "A", attemptId, "crashed-owner", leaseTtlMs = 0)
        durableAgentAttemptService.transition(scope, namespace, workflowId, "A", attemptId, "crashed-owner", AgentAttemptStatus.STARTING)

        val adapter = FakeAdapter(caseSteps(workflowId, "A")) { AgentOsExecutionVerdict.Succeeded(mapOf("summary" to "resumed")) }

        val result = sessionRunner(adapter, leaseTtlMs = 0L).runSession(scope, namespace, workflowId, repoRoot)

        assertThat(result.status).isEqualTo(WorkflowStatuses.COMPLETED)
        // The recovered attempt must NOT re-send the turn: only the durable
        // observation/reconciliation runs.
        assertThat(adapter.startTurns).isEmpty()
        assertThat(adapter.observeCalls.get()).isEqualTo(1)
        val attempt = durableAgentAttemptService.find(scope, namespace, workflowId, "A", attemptId)
        assertThat(attempt!!.status).isEqualTo(AgentAttemptStatus.SUCCEEDED)
    }

    // ----- 7. Turn longer than the Neo4j timeout ⇒ no open transaction ---
    @Test
    fun `a long remote turn runs with no open transaction and finalizes normally`() {
        val workflowId = isolatedWorkflowId("wf-bridge-long-turn")
        startSession("bridge-long-turn", workflowId, listOf(stepJson("A", "agent", "architect", emptyList())))
        val adapter = object : AgentOsExecutionAdapter {
            val turns = AtomicInteger()
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
            ) {
                turns.incrementAndGet()
                // Outlive any short transaction if one were (wrongly) held open.
                Thread.sleep(400)
            }

            override fun observeTurn(caseId: String, attemptId: String, timeoutMs: Long): AgentOsExecutionVerdict {
                Thread.sleep(400)
                return AgentOsExecutionVerdict.Succeeded(emptyMap())
            }

            override fun reconcile(caseId: String) = AgentOsExecutionVerdict.Succeeded(emptyMap())
            override fun interrupt(caseId: String, reason: String) = Unit
            override fun kill(caseId: String) = Unit
        }

        val result = sessionRunner(adapter).runSession(scope, namespace, workflowId, repoRoot)

        assertThat(result.status).isEqualTo(WorkflowStatuses.COMPLETED)
        assertThat(statusOf(workflowId, "A")).isEqualTo(WorkflowStatuses.COMPLETED)
        val attempt = durableAgentAttemptService.find(
            scope, namespace, workflowId, "A", CapabilityExecutionService.stableAttemptId(workflowId, "A"),
        )
        assertThat(attempt!!.status).isEqualTo(AgentAttemptStatus.SUCCEEDED)
    }

    // ----- 8. Same attemptId, different payload ⇒ explicit collision -----

    @Test
    fun `a replay under the same attemptId with a different command payload is an explicit collision`() {
        val workflowId = isolatedWorkflowId("wf-bridge-collision")
        startSession("bridge-collision", workflowId, listOf(stepJson("A", "agent", "architect", emptyList())))
        val attemptId = CapabilityExecutionService.stableAttemptId(workflowId, "A")
        // A prior submission registered the attempt with a DIFFERENT command payload.
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
                brief = "a completely different command",
            ),
        )
        val adapter = FakeAdapter(caseSteps(workflowId, "A")) { AgentOsExecutionVerdict.Succeeded(emptyMap()) }

        val failure = assertThrows(IdempotencyKeyCollisionException::class.java) {
            bridgeService(adapter).resolveAndRecord(scope, namespace, workflowId, agentStep("A"), repoRoot)
        }

        assertThat(failure.errorCode).isEqualTo("IDEMPOTENCY_KEY_COLLISION")
        assertThat(adapter.startTurns).isEmpty()
    }

    // ----- 9. A dependency blocked before A ⇒ no agent turn at all -----

    @Test
    fun `a blocked dependency prevents A and B from ever starting an agent turn`() {
        val workflowId = isolatedWorkflowId("wf-bridge-blocked")
        startSession(
            "bridge-blocked",
            workflowId,
            listOf(
                stepJson("pre", "code", "missing-verification", emptyList()),
                stepJson("A", "agent", "architect", listOf("pre")),
                stepJson("B", "agent", "architect", listOf("A")),
            ),
        )
        val adapter = FakeAdapter(caseSteps(workflowId, "A", "B")) { AgentOsExecutionVerdict.Succeeded(emptyMap()) }

        val result = sessionRunner(adapter).runSession(scope, namespace, workflowId, repoRoot)

        assertThat(result.status).isEqualTo(WorkflowStatuses.FAILED)
        assertThat(statusOf(workflowId, "pre")).isEqualTo(WorkflowStatuses.FAILED)
        assertThat(statusOf(workflowId, "A")).isEqualTo(WorkflowStatuses.BLOCKED)
        // B must never have started: the blocked A leaves it pending (never running/completed).
        assertThat(statusOf(workflowId, "B")).isNotEqualTo(WorkflowStatuses.RUNNING)
        assertThat(statusOf(workflowId, "B")).isNotEqualTo(WorkflowStatuses.COMPLETED)
        assertThat(adapter.startTurns).isEmpty()
    }

    // ----- 10. B starts only after A's result evidence is durable -----

    @Test
    fun `B starts only after A's agent-result evidence and successful attempt are durably committed`() {
        val workflowId = isolatedWorkflowId("wf-bridge-ordering")
        startSession(
            "bridge-ordering",
            workflowId,
            listOf(stepJson("A", "agent", "architect", emptyList()), stepJson("B", "agent", "architect", listOf("A"))),
        )
        val outputs = mapOf("summary" to "A durable output")
        val caseA = CapabilityExecutionService.stableCaseId(workflowId, "A")
        val caseB = CapabilityExecutionService.stableCaseId(workflowId, "B")
        var evidenceCommittedWhenBStarted = false
        var aStatusWhenBStarted: AgentAttemptStatus? = null
        val adapter = object : AgentOsExecutionAdapter {
            val started = CopyOnWriteArrayList<String>()

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
            ) {
                started.add(caseId)
                if (caseId == caseB) {
                    evidenceCommittedWhenBStarted = evidenceRepository
                        .list(scope, namespace, workflowId, "A")
                        .any { it.kind == CapabilityExecutionService.AGENT_RESULT_EVIDENCE_KIND && it.outcome == "pass" }
                    aStatusWhenBStarted = durableAgentAttemptService.find(
                        scope, namespace, workflowId, "A", CapabilityExecutionService.stableAttemptId(workflowId, "A"),
                    )?.status
                }
            }

            override fun observeTurn(caseId: String, attemptId: String, timeoutMs: Long): AgentOsExecutionVerdict =
                if (caseId == caseA) AgentOsExecutionVerdict.Succeeded(outputs) else AgentOsExecutionVerdict.Succeeded(emptyMap())

            override fun reconcile(caseId: String): AgentOsExecutionVerdict =
                if (caseId == caseA) AgentOsExecutionVerdict.Succeeded(outputs) else AgentOsExecutionVerdict.Succeeded(emptyMap())

            override fun interrupt(caseId: String, reason: String) = Unit

            override fun kill(caseId: String) = Unit
        }

        val result = sessionRunner(adapter).runSession(scope, namespace, workflowId, repoRoot)

        assertThat(result.status).isEqualTo(WorkflowStatuses.COMPLETED)
        // B started exactly once, and at that instant A's durable result evidence
        // and its SUCCEEDED attempt were already committed.
        assertThat(adapter.started.filter { it == caseB }).hasSize(1)
        assertThat(evidenceCommittedWhenBStarted).isTrue()
        assertThat(aStatusWhenBStarted).isEqualTo(AgentAttemptStatus.SUCCEEDED)
        assertThat(
            evidenceRepository.list(scope, namespace, workflowId, "A")
                .single { it.kind == CapabilityExecutionService.AGENT_RESULT_EVIDENCE_KIND }
                .facts["outputs"],
        ).isEqualTo(outputs)
    }

    // ----- 11. Finalization failure ⇒ reconcilable, no false success -----

    @Test
    fun `a finalization failure preserves the remote result for reconciliation and never reports success`() {
        val workflowId = isolatedWorkflowId("wf-bridge-finalize-failure")
        startSession("bridge-finalize-failure", workflowId, listOf(stepJson("A", "agent", "architect", emptyList())))
        val attemptId = CapabilityExecutionService.stableAttemptId(workflowId, "A")
        val outputs = mapOf("summary" to "remote done")
        val adapter = FakeAdapter(caseSteps(workflowId, "A")) { AgentOsExecutionVerdict.Succeeded(outputs) }

        // Phase 3 (evidence append) blows up: a simulated DB commit error.
        val failingEvidence = mockk<WorkflowEvidenceRepository>()
        every { failingEvidence.append(any(), any(), any(), any()) } throws RuntimeException("simulated DB commit failure")
        val service = CapabilityExecutionService(
            CapabilityResolver(),
            workflowRepository,
            failingEvidence,
            interactionRepository,
            attemptRepository,
            durableAgentAttemptService = durableAgentAttemptService,
            agentOsExecutionAdapter = adapter,
            transactionManager = transactionManager,
            agentLeaseTtlMs = 0L,
        )

        assertThrows(RuntimeException::class.java) {
            service.resolveAndRecord(scope, namespace, workflowId, agentStep("A"), repoRoot)
        }

        // No false success and no committed result evidence.
        assertThat(durableAgentAttemptService.find(scope, namespace, workflowId, "A", attemptId)!!.status)
            .isNotEqualTo(AgentAttemptStatus.SUCCEEDED)
        assertThat(
            evidenceRepository.list(scope, namespace, workflowId, "A")
                .filter { it.kind == CapabilityExecutionService.AGENT_RESULT_EVIDENCE_KIND },
        ).isEmpty()

        // The remote result is preserved at AgentOS: reconciliation finalizes once.
        val recoveryAdapter = FakeAdapter(caseSteps(workflowId, "A")) { AgentOsExecutionVerdict.Succeeded(outputs) }
        val report = BridgeRecoveryWorker(durableAgentAttemptService, recoveryAdapter, evidenceRepository).recover()

        assertThat(report.finalized).isEqualTo(1)
        assertThat(recoveryAdapter.reconcileCalls.get()).isEqualTo(1)
        assertThat(recoveryAdapter.startTurns).isEmpty()
        assertThat(durableAgentAttemptService.find(scope, namespace, workflowId, "A", attemptId)!!.status)
            .isEqualTo(AgentAttemptStatus.SUCCEEDED)
        assertThat(
            evidenceRepository.list(scope, namespace, workflowId, "A")
                .filter { it.kind == CapabilityExecutionService.AGENT_RESULT_EVIDENCE_KIND },
        ).hasSize(1)
    }
}
