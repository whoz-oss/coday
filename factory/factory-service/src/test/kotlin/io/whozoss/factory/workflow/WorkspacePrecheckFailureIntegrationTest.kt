package io.whozoss.factory.workflow

import io.mockk.every
import io.mockk.mockk
import io.mockk.verify
import io.whozoss.factory.Neo4jDomainIntegrationTest
import io.whozoss.factory.adapter.agentos.AgentOsExecutionAdapter
import io.whozoss.factory.agentattempt.persistence.AgentStepAttemptRepository
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
import io.whozoss.factory.workflow.domain.WorkflowBlockerCodes
import io.whozoss.factory.workflow.domain.hashWorkflowDefinition
import io.whozoss.factory.workflow.persistence.HumanInteractionRepository
import io.whozoss.factory.workflow.persistence.WorkflowEvidenceRepository
import io.whozoss.factory.workflow.persistence.WorkflowRepository
import io.whozoss.factory.workflow.service.OutcomeTransitionService
import io.whozoss.factory.workflow.service.SessionRunResult
import io.whozoss.factory.workflow.service.SessionRunService
import io.whozoss.factory.workflow.service.WorkflowService
import io.whozoss.factory.workflow.sse.WorkflowSseHub
import io.whozoss.factory.workspace.WorkspacePrecheckCodes
import io.whozoss.factory.workspace.WorkspacePrecheckException
import io.whozoss.factory.workspace.WorkspacePrecheckService
import java.nio.file.Path
import java.util.UUID
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Assertions.assertThrows
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.transaction.PlatformTransactionManager

/**
 * Non-regression of WZ-36423: a workspace pre-check refusal must be recorded and surfaced, never left as an
 * escaping exception that strands the run in `ready` ("Waiting") with no blocker.
 *
 * The real Spring beans are used. The AgentOS boundary is a relaxed mock: a refused run must never reach it.
 */
class WorkspacePrecheckFailureIntegrationTest : Neo4jDomainIntegrationTest() {

    @Autowired private lateinit var workflowService: WorkflowService
    @Autowired private lateinit var workflowRepository: WorkflowRepository
    @Autowired private lateinit var evidenceRepository: WorkflowEvidenceRepository
    @Autowired private lateinit var interactionRepository: HumanInteractionRepository
    @Autowired private lateinit var attemptRepository: AgentStepAttemptRepository
    @Autowired private lateinit var durableAgentAttemptService: DurableAgentAttemptService
    @Autowired private lateinit var sseHub: WorkflowSseHub
    @Autowired private lateinit var transactionManager: PlatformTransactionManager
    @Autowired private lateinit var outcomeTransitions: OutcomeTransitionService

    @TempDir
    lateinit var repoRoot: Path

    private val namespace = "precheck-namespace"
    private val actor = "engineer-precheck"

    private fun stepJson(id: String, dependsOn: List<String>): Map<String, Any?> = linkedMapOf(
        "id" to id,
        "name" to "Step $id",
        "responsibility" to mapOf("kind" to "agent", "name" to "reviewer"),
        "dependsOn" to dependsOn,
    )

    private fun startWorkflow(workflowType: String, workflowId: String, outcomeManaged: Boolean = false) {
        val first = if (outcomeManaged) {
            stepJson("A", emptyList()) +
                ("outcomeTransitions" to mapOf("fail" to mapOf("action" to "return", "target" to "A")))
        } else {
            stepJson("A", emptyList())
        }
        val raw = linkedMapOf<String, Any?>(
            "schemaVersion" to "1",
            "workflowType" to workflowType,
            "version" to "1.0.0",
            "title" to "Session $workflowType",
            "steps" to listOf(first, stepJson("B", listOf("A"))),
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

    private fun sessionRunner(
        adapter: AgentOsExecutionAdapter,
        precheck: WorkspacePrecheckService?,
        outcomeService: OutcomeTransitionService? = null,
    ): SessionRunService = SessionRunService(
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
        workspacePrecheckService = precheck,
        outcomeTransitionService = outcomeService,
    )

    private fun refusal(): WorkspacePrecheckException = WorkspacePrecheckException(
        WorkspacePrecheckCodes.REQUIRED_AGENTS_MISSING,
        "Required agent(s) reviewer are not available in namespace '$namespace'.",
        mapOf(
            "missingAgents" to listOf("reviewer"),
            "availableAgents" to listOf("architect"),
            "remedy" to "Publish/enable the required agents in the namespace.",
        ),
    )

    private fun statusOf(workflowId: String, stepId: String): String =
        workflowRepository.findStepStates(scope, namespace, workflowId).first { it.stepId == stepId }.status

    private fun precheckEvidence(workflowId: String) =
        evidenceRepository.list(scope, namespace, workflowId).filter { it.kind == "workspace-precheck" }

    @Test
    fun `a refused workspace precheck fails the run with a durable reason and dispatches nothing`() {
        val workflowId = "wf-precheck-${UUID.randomUUID()}"
        startWorkflow("precheck-refused", workflowId)
        val adapter = mockk<AgentOsExecutionAdapter>(relaxed = true)
        val precheck = mockk<WorkspacePrecheckService>()
        every { precheck.precheck(any(), any(), any(), any(), any()) } throws refusal()

        val result: SessionRunResult = sessionRunner(adapter, precheck).runSession(scope, namespace, workflowId, repoRoot)

        assertThat(result.status).isEqualTo(WorkflowStatuses.FAILED)
        assertThat(statusOf(workflowId, "A")).isEqualTo(WorkflowStatuses.FAILED)
        assertThat(statusOf(workflowId, "B")).isEqualTo(WorkflowStatuses.BLOCKED)
        assertThat(workflowRepository.findInstance(scope, namespace, workflowId)!!.projection["status"])
            .isEqualTo(WorkflowStatuses.FAILED)

        val refusals = precheckEvidence(workflowId)
        assertThat(refusals).hasSize(1)
        val evidence = refusals.single()
        assertThat(evidence.stepId).isNull()
        assertThat(evidence.outcome).isEqualTo("failed")
        assertThat(evidence.facts["code"]).isEqualTo(WorkspacePrecheckCodes.REQUIRED_AGENTS_MISSING)
        assertThat(evidence.facts["missingAgents"]).isEqualTo(listOf("reviewer"))
        assertThat(evidence.facts["remedy"]).isEqualTo("Publish/enable the required agents in the namespace.")

        val stepEvidence = evidenceRepository.list(scope, namespace, workflowId, "A")
            .single { it.kind == "session-step" && it.outcome == WorkflowStatuses.FAILED }
        assertThat(stepEvidence.facts["code"]).isEqualTo(WorkspacePrecheckCodes.REQUIRED_AGENTS_MISSING)
        assertThat(stepEvidence.facts).doesNotContainKeys("executed", "verification")

        verify(exactly = 0) { adapter.createOrRecoverExecution(any(), any(), any(), any(), any(), any(), any(), any()) }
        verify(exactly = 0) { adapter.startTurn(any(), any(), any(), any(), any(), any()) }

        val actions = workflowService.workflowActions(scope, namespace, workflowId, canReply = true, actorId = actor)
        val blocker = actions.blockers.single { it.code == WorkflowBlockerCodes.WORKSPACE_PRECHECK_FAILED }
        assertThat(blocker.stepId).isNull()
        assertThat(blocker.details).isEqualTo(WorkspacePrecheckCodes.REQUIRED_AGENTS_MISSING)
        assertThat(blocker.message).contains("reviewer").contains("Remedy: Publish/enable the required agents")
    }

    @Test
    fun `a replayed continuation of a refused run appends no second precheck evidence`() {
        val workflowId = "wf-precheck-replay-${UUID.randomUUID()}"
        startWorkflow("precheck-replay", workflowId)
        val adapter = mockk<AgentOsExecutionAdapter>(relaxed = true)
        val precheck = mockk<WorkspacePrecheckService>()
        every { precheck.precheck(any(), any(), any(), any(), any()) } throws refusal()
        val runner = sessionRunner(adapter, precheck)

        runner.runSession(scope, namespace, workflowId, repoRoot)
        val second = runner.runSession(scope, namespace, workflowId, repoRoot)

        assertThat(second.status).isEqualTo(WorkflowStatuses.FAILED)
        assertThat(precheckEvidence(workflowId)).hasSize(1)
        assertThat(statusOf(workflowId, "A")).isEqualTo(WorkflowStatuses.FAILED)
        assertThat(statusOf(workflowId, "B")).isEqualTo(WorkflowStatuses.BLOCKED)
        verify(exactly = 0) { adapter.createOrRecoverExecution(any(), any(), any(), any(), any(), any(), any(), any()) }
    }

    @Test
    fun `a refused precheck mid-run fails only the ready step and keeps the completed one`() {
        val workflowId = "wf-precheck-midrun-${UUID.randomUUID()}"
        startWorkflow("precheck-midrun", workflowId)
        workflowRepository.upsertStepState(
            scope,
            io.whozoss.factory.workflow.domain.WorkflowStepStateRecord(namespace, workflowId, "A", 1, WorkflowStatuses.COMPLETED, emptyMap()),
        )
        workflowRepository.upsertStepState(
            scope,
            io.whozoss.factory.workflow.domain.WorkflowStepStateRecord(namespace, workflowId, "B", 1, WorkflowStatuses.READY, emptyMap()),
        )
        val adapter = mockk<AgentOsExecutionAdapter>(relaxed = true)
        val precheck = mockk<WorkspacePrecheckService>()
        every { precheck.precheck(any(), any(), any(), any(), any()) } throws refusal()

        val result = sessionRunner(adapter, precheck).runSession(scope, namespace, workflowId, repoRoot)

        assertThat(result.status).isEqualTo(WorkflowStatuses.FAILED)
        assertThat(statusOf(workflowId, "A")).isEqualTo(WorkflowStatuses.COMPLETED)
        assertThat(statusOf(workflowId, "B")).isEqualTo(WorkflowStatuses.FAILED)
        verify(exactly = 0) { adapter.createOrRecoverExecution(any(), any(), any(), any(), any(), any(), any(), any()) }
    }

    @Test
    fun `a refused precheck of an outcome-managed run fails the step without routing a correction`() {
        val workflowId = "wf-precheck-outcome-${UUID.randomUUID()}"
        startWorkflow("precheck-outcome", workflowId, outcomeManaged = true)
        val adapter = mockk<AgentOsExecutionAdapter>(relaxed = true)
        val precheck = mockk<WorkspacePrecheckService>()
        every { precheck.precheck(any(), any(), any(), any(), any()) } throws refusal()

        val result = sessionRunner(adapter, precheck, outcomeTransitions).runSession(scope, namespace, workflowId, repoRoot)

        assertThat(result.status).isEqualTo(WorkflowStatuses.FAILED)
        assertThat(statusOf(workflowId, "A")).isEqualTo(WorkflowStatuses.FAILED)
        assertThat(workflowRepository.findInstance(scope, namespace, workflowId)!!.projection["correction"]).isNull()
        verify(exactly = 0) { adapter.createOrRecoverExecution(any(), any(), any(), any(), any(), any(), any(), any()) }
    }

    @Test
    fun `a precheck failure other than a refusal still propagates unchanged`() {
        val workflowId = "wf-precheck-other-${UUID.randomUUID()}"
        startWorkflow("precheck-other", workflowId)
        val adapter = mockk<AgentOsExecutionAdapter>(relaxed = true)
        val precheck = mockk<WorkspacePrecheckService>()
        every { precheck.precheck(any(), any(), any(), any(), any()) } throws IllegalStateException("boom")

        assertThrows(IllegalStateException::class.java) {
            sessionRunner(adapter, precheck).runSession(scope, namespace, workflowId, repoRoot)
        }
        assertThat(precheckEvidence(workflowId)).isEmpty()
    }
}
