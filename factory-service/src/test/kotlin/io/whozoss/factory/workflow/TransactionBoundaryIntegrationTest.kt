package io.whozoss.factory.workflow

import io.mockk.every
import io.mockk.mockk
import io.whozoss.factory.Neo4jDomainIntegrationTest
import io.whozoss.factory.adapter.agentos.AgentOsAdapterProperties
import io.whozoss.factory.adapter.agentos.AgentOsExecutionAdapter
import io.whozoss.factory.agentattempt.persistence.AgentStepAttemptRepository
import io.whozoss.factory.agentattempt.service.DurableAgentAttemptService
import io.whozoss.factory.capability.AgentTurnCapability
import io.whozoss.factory.capability.AgentTurnRequest
import io.whozoss.factory.capability.AgentTurnResult
import io.whozoss.factory.capability.CapabilityExecutionService
import io.whozoss.factory.capability.CapabilityResolver
import io.whozoss.factory.workflow.domain.ControllerExecutionInput
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
import io.whozoss.factory.workflow.service.SessionRunService
import io.whozoss.factory.workflow.service.WorkflowService
import io.whozoss.factory.workflow.sse.WorkflowSseHub
import java.nio.file.Path
import java.util.concurrent.CountDownLatch
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicInteger
import org.assertj.core.api.Assertions.assertThat
import org.assertj.core.api.Assertions.assertThatThrownBy
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.transaction.PlatformTransactionManager
import org.springframework.transaction.support.TransactionSynchronizationManager

/**
 * Integration tests of the tightened transaction boundaries (W8.5).
 *
 * They use the REAL Spring beans (and the autowired [PlatformTransactionManager]
 * when the production `@Service` is wired into a bespoke instance), so the
 * `REQUIRES_NEW` boundaries and the non-transactional orchestrators are exercised
 * exactly as in production:
 *  - external capability execution runs with NO active Neo4j transaction;
 *  - a failure is recovered in a fresh, short transaction (never masked by an
 *    unhandled 500);
 *  - two concurrent runs of the same workflow execute the external capability
 *    exactly once (atomic, lock-serialised claim);
 *  - the human reply commits durably BEFORE resumption, so a failing resumption
 *    cannot roll it back.
 */
class TransactionBoundaryIntegrationTest : Neo4jDomainIntegrationTest() {

    @Autowired
    private lateinit var workflowService: WorkflowService

    @Autowired
    private lateinit var sessionRunService: SessionRunService

    @Autowired
    private lateinit var workflowRepository: WorkflowRepository

    @Autowired
    private lateinit var evidenceRepository: WorkflowEvidenceRepository

    @Autowired
    private lateinit var interactionRepository: HumanInteractionRepository

    @Autowired
    private lateinit var attemptRepository: AgentStepAttemptRepository

    @Autowired
    private lateinit var sseHub: WorkflowSseHub

    @Autowired
    private lateinit var transactionManager: PlatformTransactionManager

    @TempDir
    lateinit var repoRoot: Path

    private val namespace = "2b6f8d2f-8d1a-4f0e-9b6e-tx-boundaries"

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
        val result = workflowService.start(
            scope,
            namespace,
            WorkflowStartCommand(workflowId = workflowId, workflowType = workflowType, title = "Session $workflowType"),
            ControllerExecutionInput(runtimeId = "test-runtime", kind = "agentos", agentId = "runner", namespaceId = namespace),
        )
        assertThat(result.status).isEqualTo(201)
    }

    private fun statusOf(workflowId: String, stepId: String): String =
        workflowRepository.findStepStates(scope, namespace, workflowId).first { it.stepId == stepId }.status

    /** A sequencer whose capability execution is the REAL, transaction-aware service. */
    private fun sessionWith(capability: AgentTurnCapability): SessionRunService =
        SessionRunService(
            workflowRepository,
            evidenceRepository,
            interactionRepository,
            CapabilityExecutionService(
                CapabilityResolver(capability),
                workflowRepository,
                evidenceRepository,
                interactionRepository,
                attemptRepository,
                durableAgentAttemptService = mockk<DurableAgentAttemptService>(relaxed = true),
                agentOsExecutionAdapter = mockk<AgentOsExecutionAdapter>(relaxed = true),
                agentStepResultService = null,
                transactionManager = transactionManager,
                agentOsAdapterProperties = AgentOsAdapterProperties(enabled = false),
            ),
            sseHub,
            oracleDefinitionRegistry = null,
            oracleExecutionService = null,
            transactionManager = transactionManager,
        )

    // ----- Test Case 1: external execution outside any transaction -------

    @Test
    fun `external agent execution runs with no active transaction and completes`() {
        val workflowId = "wf-tx-isolation"
        startSession("tx-isolation", workflowId, listOf(stepJson("s1", "agent", "architect", emptyList())))
        val transactionActiveDuringTurn = AtomicBoolean(true)
        val capability = object : AgentTurnCapability {
            override fun executeAgentTurn(request: AgentTurnRequest): AgentTurnResult {
                transactionActiveDuringTurn.set(TransactionSynchronizationManager.isActualTransactionActive())
                // Long enough to outlive a short transaction if one were held open.
                Thread.sleep(300)
                return AgentTurnResult.Completed("PASS")
            }
        }

        val result = sessionWith(capability).runSession(scope, namespace, workflowId, repoRoot)

        assertThat(result.status).isEqualTo(WorkflowStatuses.COMPLETED)
        assertThat(statusOf(workflowId, "s1")).isEqualTo(WorkflowStatuses.COMPLETED)
        assertThat(transactionActiveDuringTurn.get())
            .describedAs("external capability execution must run outside any active Neo4j transaction")
            .isFalse()
        val evidence = evidenceRepository.list(scope, namespace, workflowId)
        assertThat(evidence).anyMatch { it.kind == "agent-turn" && it.outcome == "pass" }
    }

    @Test
    fun `a failing external execution is recovered in a fresh transaction without masking the cause`() {
        val workflowId = "wf-tx-failure"
        startSession("tx-failure", workflowId, listOf(stepJson("s1", "agent", "architect", emptyList())))
        // A capability whose execution blows up OUTSIDE any transaction: the
        // sequencer must persist the failure evidence in a fresh short
        // transaction and return FAILED, not surface an unhandled 500.
        val failingCapabilities = mockk<CapabilityExecutionService>()
        every { failingCapabilities.resolveAndRecord(any(), any(), any(), any(), any(), any()) } throws
            RuntimeException("Cannot run more queries in this transaction (simulated)")
        val runner = SessionRunService(
            workflowRepository,
            evidenceRepository,
            interactionRepository,
            failingCapabilities,
            sseHub,
            oracleDefinitionRegistry = null,
            oracleExecutionService = null,
            transactionManager = transactionManager,
        )

        val result = runner.runSession(scope, namespace, workflowId, repoRoot)

        assertThat(result.status).isEqualTo(WorkflowStatuses.FAILED)
        assertThat(statusOf(workflowId, "s1")).isEqualTo(WorkflowStatuses.FAILED)
        val evidence = evidenceRepository.list(scope, namespace, workflowId)
        assertThat(evidence).anyMatch { it.kind == "session-step" && it.facts["code"] == "STEP_EXECUTION_ERROR" }
        val transitions = workflowRepository.listTransitionTimestamps(scope, namespace, workflowId)
        assertThat(transitions).isNotEmpty()
    }

    // ----- Test Case 3: concurrent run protection (atomic claim) ---------

    @Test
    fun `two concurrent runs execute the external capability exactly once`() {
        val workflowId = "wf-tx-concurrent"
        startSession("tx-concurrent", workflowId, listOf(stepJson("s1", "agent", "architect", emptyList())))
        val executions = AtomicInteger(0)
        val capability = object : AgentTurnCapability {
            override fun executeAgentTurn(request: AgentTurnRequest): AgentTurnResult {
                executions.incrementAndGet()
                Thread.sleep(400)
                return AgentTurnResult.Completed("PASS")
            }
        }
        val runner = sessionWith(capability)
        val start = CountDownLatch(1)
        val executor = Executors.newFixedThreadPool(2)
        try {
            val futures = (1..2).map {
                executor.submit<Any> {
                    start.await()
                    runner.runSession(scope, namespace, workflowId, repoRoot)
                }
            }
            start.countDown()
            futures.forEach { it.get(30, TimeUnit.SECONDS) }
        } finally {
            executor.shutdownNow()
        }

        assertThat(executions.get()).isEqualTo(1)
        assertThat(statusOf(workflowId, "s1")).isEqualTo(WorkflowStatuses.COMPLETED)
    }

    // ----- Test Case 2: human reply durability vs resumption -------------

    @Test
    fun `human reply is durably committed before a failing resumption`() {
        val workflowId = "wf-tx-human-reply"
        startSession(
            "tx-human-reply",
            workflowId,
            listOf(
                stepJson("s1", "human", "reviewer", emptyList()),
                stepJson("s2", "agent", "architect", listOf("s1")),
            ),
        )
        // Open the DAG checkpoint interaction through the real sequencer.
        sessionRunService.runSession(scope, namespace, workflowId, repoRoot)
        val interaction = interactionRepository.list(scope, namespace, workflowId, openOnly = true).single()
        assertThat(interaction.interactionType).isEqualTo("checkpoint")

        // Resumption blows up: it runs AFTER the reply transaction committed.
        val failingRunner = mockk<SessionRunService>()
        every { failingRunner.runSession(any(), any(), any(), any(), any()) } throws
            RuntimeException("resumption exploded")
        val service = WorkflowService(
            workflowRepository,
            evidenceRepository,
            interactionRepository,
            sseHub,
            sessionRunService = failingRunner,
            agentOsProxyClient = null,
            transactionManager = transactionManager,
        )

        assertThatThrownBy {
            service.replyInteraction(
                scope,
                namespace,
                workflowId,
                interaction.interactionId,
                interaction.revision,
                "approve",
                "looks good",
                "alice",
                repoRoot,
            )
        }.isInstanceOf(RuntimeException::class.java).hasMessageContaining("resumption exploded")

        // The human decision is durable despite the resumption failure.
        assertThat(interactionRepository.find(scope, namespace, workflowId, interaction.interactionId)?.status)
            .isEqualTo("closed")
        assertThat(evidenceRepository.list(scope, namespace, workflowId))
            .anyMatch { it.kind == "human-decision" && it.outcome == "pass" }
    }

    // ----- Test Case 4: strict CAS on claim ---------------------------------

    @Test
    fun `a running step cannot be re-claimed from ready`() {
        val workflowId = "wf-tx-claim-cas"
        startSession("tx-claim-cas", workflowId, listOf(stepJson("s1", "agent", "architect", emptyList())))
        // The sequencer creates the step-state row on its first evaluation; here
        // it is seeded directly so the claim CAS is exercised in isolation.
        workflowRepository.upsertStepState(
            scope,
            WorkflowStepStateRecord(
                namespaceId = namespace,
                workflowId = workflowId,
                stepId = "s1",
                revision = 1,
                status = WorkflowStatuses.READY,
                payload = emptyMap(),
            ),
        )
        // The first claim wins `ready -> running`...
        assertThat(
            workflowRepository.claimStep(scope, namespace, workflowId, "s1", listOf("ready"), emptyMap()),
        ).isTrue()
        // ...and a second claim from `ready` is rejected (atomic claim).
        assertThat(
            workflowRepository.claimStep(scope, namespace, workflowId, "s1", listOf("ready"), emptyMap()),
        ).isFalse()
        assertThat(statusOf(workflowId, "s1")).isEqualTo(WorkflowStatuses.RUNNING)
    }
}
