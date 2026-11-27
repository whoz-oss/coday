package io.whozoss.factory.workflow

import io.mockk.every
import io.mockk.mockk
import io.mockk.verify
import io.whozoss.factory.Neo4jDomainIntegrationTest
import io.whozoss.factory.capability.CapabilityExecutionService
import io.whozoss.factory.persistence.TenantScope
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
import io.whozoss.factory.workspace.WorkspacePrecheckCodes
import io.whozoss.factory.workspace.WorkspacePrecheckException
import io.whozoss.factory.workspace.WorkspacePrecheckService
import java.nio.file.Path
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import org.springframework.beans.factory.annotation.Autowired

/**
 * A refused workspace pre-check must end the run `failed` with a durable, explainable reason, instead of
 * letting the exception escape (continuation dead, run stuck in `ready`, nothing recorded).
 */
class WorkspacePrecheckFailureIntegrationTest : Neo4jDomainIntegrationTest() {

    @Autowired
    private lateinit var workflowService: WorkflowService

    @Autowired
    private lateinit var workflowRepository: WorkflowRepository

    @Autowired
    private lateinit var evidenceRepository: WorkflowEvidenceRepository

    @Autowired
    private lateinit var interactionRepository: HumanInteractionRepository

    @TempDir
    lateinit var repoRoot: Path

    private val namespace = "2b6f8d2f-8d1a-4f0e-9b6e-precheck-refusal"

    private fun stepJson(id: String, name: String, dependsOn: List<String>): Map<String, Any?> = linkedMapOf(
        "id" to id,
        "name" to "Step $id",
        "responsibility" to mapOf("kind" to "agent", "name" to name),
        "dependsOn" to dependsOn,
    )

    private fun startTwoStepSession(workflowId: String) {
        val raw = linkedMapOf<String, Any?>(
            "schemaVersion" to "1",
            "workflowType" to workflowId,
            "version" to "1.0.0",
            "title" to "Session $workflowId",
            "steps" to listOf(stepJson("s1", "reviewer", emptyList()), stepJson("s2", "reviewer", listOf("s1"))),
        )
        val valid = WorkflowDefinitionValidator.validate(raw) as WorkflowDefinitionValidation.Valid
        workflowService.registerDefinition(
            scope,
            WorkflowDefinitionRecord(
                workflowType = workflowId,
                version = "1.0.0",
                definitionHash = hashWorkflowDefinition(valid.definition),
                definition = valid.definition,
            ),
        )
        val result = workflowService.start(
            scope,
            namespace,
            WorkflowStartCommand(workflowId = workflowId, workflowType = workflowId, title = "Session $workflowId"),
            ControllerExecutionInput(runtimeId = "test-runtime", kind = "agentos", agentId = "runner", namespaceId = namespace),
        )
        assertThat(result.status).isEqualTo(201)
    }

    private fun refusingPrecheck(): WorkspacePrecheckService = mockk {
        every { precheck(any(), any(), any(), any(), any()) } throws WorkspacePrecheckException(
            WorkspacePrecheckCodes.REQUIRED_AGENTS_MISSING,
            "Required agent(s) reviewer are not available in namespace '$namespace'.",
            mapOf(
                "namespaceId" to namespace,
                "missingAgents" to listOf("reviewer"),
                "availableAgents" to listOf("architect"),
                "remedy" to "Publish/enable the required agents in the namespace.",
            ),
        )
    }

    private fun statusOf(workflowId: String, stepId: String): String =
        workflowRepository.findStepStates(scope, namespace, workflowId).first { it.stepId == stepId }.status

    private fun runner(capability: CapabilityExecutionService, hub: WorkflowSseHub) = SessionRunService(
        workflowRepository,
        evidenceRepository,
        interactionRepository,
        capability,
        hub,
        workspacePrecheckService = refusingPrecheck(),
    )

    private fun precheckEvidence(workflowId: String) =
        evidenceRepository.list(scope, namespace, workflowId).filter { it.kind == "workspace-precheck" }

    @Test
    fun `a refused pre-check fails the ready step, blocks its dependant and records one evidence`() {
        val workflowId = "wf-precheck-refused"
        startTwoStepSession(workflowId)
        // A strict mock: any capability call (agent turn, workspace preparation) would fail the test.
        val capability = mockk<CapabilityExecutionService>()
        val hub = mockk<WorkflowSseHub>(relaxed = true)
        val service = runner(capability, hub)

        val result = service.runSession(scope, namespace, workflowId, repoRoot)

        assertThat(result.status).isEqualTo(WorkflowStatuses.FAILED)
        assertThat(statusOf(workflowId, "s1")).isEqualTo(WorkflowStatuses.FAILED)
        assertThat(statusOf(workflowId, "s2")).isEqualTo(WorkflowStatuses.BLOCKED)
        val evidence = precheckEvidence(workflowId).single()
        assertThat(evidence.stepId).isNull()
        assertThat(evidence.outcome).isEqualTo(WorkflowStatuses.FAILED)
        assertThat(evidence.facts["code"]).isEqualTo(WorkspacePrecheckCodes.REQUIRED_AGENTS_MISSING)
        assertThat(evidence.facts["message"].toString()).contains("reviewer")
        assertThat(evidence.facts["missingAgents"]).isEqualTo(listOf("reviewer"))
        assertThat(evidence.facts["remedy"]).isEqualTo("Publish/enable the required agents in the namespace.")
        val stepEvidence = evidenceRepository.list(scope, namespace, workflowId, "s1")
            .single { it.kind == "session-step" && it.outcome == WorkflowStatuses.FAILED }
        assertThat(stepEvidence.facts["code"]).isEqualTo(WorkspacePrecheckCodes.REQUIRED_AGENTS_MISSING)
        assertThat(stepEvidence.facts).doesNotContainKey("executed")
        val instance = workflowRepository.findInstance(scope, namespace, workflowId)!!
        assertThat(instance.projection["status"]).isEqualTo(WorkflowStatuses.FAILED)
        assertThat(instance.instance["status"]).isEqualTo(WorkflowStatuses.FAILED)
        verify(atLeast = 1) { hub.publish(any<TenantScope>(), any<String>(), any<Any>()) }
        verify(exactly = 0) { capability.resolveAndRecord(any(), any(), any(), any(), any(), any(), any()) }
    }

    @Test
    fun `a second run hitting the same refusal stays failed and appends no second evidence`() {
        val workflowId = "wf-precheck-replay"
        startTwoStepSession(workflowId)
        val service = runner(mockk<CapabilityExecutionService>(), mockk<WorkflowSseHub>(relaxed = true))

        assertThat(service.runSession(scope, namespace, workflowId, repoRoot).status).isEqualTo(WorkflowStatuses.FAILED)
        assertThat(service.runSession(scope, namespace, workflowId, repoRoot).status).isEqualTo(WorkflowStatuses.FAILED)

        assertThat(precheckEvidence(workflowId)).hasSize(1)
        assertThat(statusOf(workflowId, "s1")).isEqualTo(WorkflowStatuses.FAILED)
        assertThat(statusOf(workflowId, "s2")).isEqualTo(WorkflowStatuses.BLOCKED)
    }

    @Test
    fun `a step completed before the refusal stays completed and only the released step fails`() {
        val workflowId = "wf-precheck-mid-run"
        startTwoStepSession(workflowId)
        workflowRepository.upsertStepState(
            scope,
            WorkflowStepStateRecord(namespace, workflowId, "s1", 1, WorkflowStatuses.COMPLETED, emptyMap()),
        )
        val service = runner(mockk<CapabilityExecutionService>(), mockk<WorkflowSseHub>(relaxed = true))

        val result = service.runSession(scope, namespace, workflowId, repoRoot)

        assertThat(result.status).isEqualTo(WorkflowStatuses.FAILED)
        assertThat(statusOf(workflowId, "s1")).isEqualTo(WorkflowStatuses.COMPLETED)
        assertThat(statusOf(workflowId, "s2")).isEqualTo(WorkflowStatuses.FAILED)
        assertThat(precheckEvidence(workflowId)).hasSize(1)
    }
}
