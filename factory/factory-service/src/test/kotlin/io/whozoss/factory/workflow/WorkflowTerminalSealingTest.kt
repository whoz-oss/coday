package io.whozoss.factory.workflow

import io.whozoss.factory.Neo4jDomainIntegrationTest
import io.whozoss.factory.persistence.TenantScope
import io.whozoss.factory.workflow.domain.ControllerExecutionInput
import io.whozoss.factory.workflow.domain.HumanInteractionRecord
import io.whozoss.factory.workflow.domain.WorkflowDefinitionRecord
import io.whozoss.factory.workflow.domain.WorkflowDefinitionValidation
import io.whozoss.factory.workflow.domain.WorkflowDefinitionValidator
import io.whozoss.factory.workflow.domain.WorkflowErrorCodes
import io.whozoss.factory.workflow.domain.WorkflowException
import io.whozoss.factory.workflow.domain.WorkflowExecution
import io.whozoss.factory.workflow.domain.WorkflowStartCommand
import io.whozoss.factory.workflow.domain.hashWorkflowDefinition
import io.whozoss.factory.workflow.domain.linkedWorkflowRelations
import io.whozoss.factory.workflow.persistence.HumanInteractionRepository
import io.whozoss.factory.workflow.persistence.WorkflowRepository
import io.whozoss.factory.workflow.service.WorkflowHttpResult
import io.whozoss.factory.workflow.service.WorkflowService
import org.assertj.core.api.Assertions.assertThat
import org.assertj.core.api.Assertions.assertThatThrownBy
import org.junit.jupiter.api.Test
import org.springframework.beans.factory.annotation.Autowired

/**
 * Phase 10 attestation: a workflow instance whose overall run status is
 * terminal (`completed` / `failed` / `cancelled`) is SEALED — final and
 * immutable.
 *
 * `factory-service/src/main/kotlin/io/whozoss/factory/workflow/service/WorkflowService.kt`
 * rejects any mutation of a sealed instance with `WORKFLOW_SEALED` (HTTP 409)
 * in the `transition`, `codeTransition` and human-resolution paths, BEFORE the
 * per-step state machine runs. Reopening a terminal workflow is strictly
 * forbidden: resuming or re-running the requirement happens exclusively by
 * starting a NEW workflow linked to the sealed predecessor via
 * `factory-service/src/main/kotlin/io/whozoss/factory/workflow/domain/WorkflowInstance.kt`
 * `linkedWorkflowRelations`. The lifecycle axis (`remove` / `restore`) is
 * orthogonal: restoring a soft-deleted projection never reopens a sealed run.
 */
class WorkflowTerminalSealingTest : Neo4jDomainIntegrationTest() {

    @Autowired
    private lateinit var service: WorkflowService

    @Autowired
    private lateinit var repository: WorkflowRepository

    @Autowired
    private lateinit var interactionRepository: HumanInteractionRepository

    private val organizationScope: TenantScope get() = scope
    private val namespace = "ns-terminal-sealing"

    // ------------------------------------------------------------------
    // Sealed terminal statuses
    // ------------------------------------------------------------------

    @Test
    fun `a completed workflow is sealed against transition codeTransition and human resolution`() {
        registerDefinition()
        startWorkflow("wf-completed")
        driveHumanGate("wf-completed", "approve")
        assertThat(runStatus("wf-completed")).isEqualTo("completed")

        assertThatThrownBy { service.transition(organizationScope, namespace, "wf-completed", transitionRequest("wf-completed", 3), execution()) }
            .isInstanceOf(WorkflowException::class.java)
            .extracting("errorCode")
            .isEqualTo(WorkflowErrorCodes.WORKFLOW_SEALED)

        assertThatThrownBy { service.codeTransition(organizationScope, namespace, "wf-completed", transitionRequest("wf-completed", 3)) }
            .isInstanceOf(WorkflowException::class.java)
            .extracting("errorCode")
            .isEqualTo(WorkflowErrorCodes.WORKFLOW_SEALED)

        insertWaitingInteraction("wf-completed", "interaction-late")
        assertThatThrownBy {
            service.replyInteraction(organizationScope, namespace, "wf-completed", "interaction-late", 1, "approve", "late", "alice")
        }
            .isInstanceOf(WorkflowException::class.java)
            .extracting("errorCode")
            .isEqualTo(WorkflowErrorCodes.WORKFLOW_SEALED)

        // The sealed instance was not mutated by any of the rejected calls.
        assertThat(repository.findInstance(organizationScope, namespace, "wf-completed")?.revision).isEqualTo(3)
        assertThat(runStatus("wf-completed")).isEqualTo("completed")
    }

    @Test
    fun `a failed workflow is sealed against any further transition`() {
        registerDefinition()
        startWorkflow("wf-failed")
        driveHumanGate("wf-failed", "reject")
        assertThat(runStatus("wf-failed")).isEqualTo("failed")

        assertThatThrownBy { service.transition(organizationScope, namespace, "wf-failed", transitionRequest("wf-failed", 3), execution()) }
            .isInstanceOf(WorkflowException::class.java)
            .extracting("errorCode")
            .isEqualTo(WorkflowErrorCodes.WORKFLOW_SEALED)
        assertThatThrownBy { service.codeTransition(organizationScope, namespace, "wf-failed", transitionRequest("wf-failed", 3)) }
            .isInstanceOf(WorkflowException::class.java)
            .extracting("errorCode")
            .isEqualTo(WorkflowErrorCodes.WORKFLOW_SEALED)
    }

    @Test
    fun `a cancelled workflow is sealed against any further transition`() {
        registerDefinition()
        startWorkflow("wf-cancelled")
        forceRunStatus("wf-cancelled", "cancelled")
        assertThat(runStatus("wf-cancelled")).isEqualTo("cancelled")

        assertThatThrownBy { service.transition(organizationScope, namespace, "wf-cancelled", transitionRequest("wf-cancelled", 2), execution()) }
            .isInstanceOf(WorkflowException::class.java)
            .extracting("errorCode")
            .isEqualTo(WorkflowErrorCodes.WORKFLOW_SEALED)
    }

    @Test
    fun `restoring a soft deleted projection never reopens a sealed run`() {
        registerDefinition()
        startWorkflow("wf-restored")
        driveHumanGate("wf-restored", "approve")
        assertThat(runStatus("wf-restored")).isEqualTo("completed")

        // Lifecycle axis (orthogonal to the run seal): remove then restore.
        val removed = service.remove(organizationScope, namespace, "wf-restored")
        assertThat(removed.status).isEqualTo(200)
        val restored = service.restore(organizationScope, namespace, "wf-restored")
        assertThat(restored.status).isEqualTo(200)

        // The projection-path restore recovers the projection lifecycle; bring
        // the instance lifecycle back too so only the run seal can reject.
        assertThat(repository.setInstanceStatus(organizationScope, namespace, "wf-restored", "removed", "active")).isTrue()

        // The run status is still terminal and sealed: no reopening.
        assertThat(runStatus("wf-restored")).isEqualTo("completed")
        assertThatThrownBy { service.transition(organizationScope, namespace, "wf-restored", transitionRequest("wf-restored", 3), execution()) }
            .isInstanceOf(WorkflowException::class.java)
            .extracting("errorCode")
            .isEqualTo(WorkflowErrorCodes.WORKFLOW_SEALED)
    }

    @Test
    fun `resuming a sealed requirement starts a new workflow linked to the sealed predecessor`() {
        registerDefinition()
        startWorkflow("wf-previous")
        driveHumanGate("wf-previous", "approve")
        val predecessorBefore = repository.findInstance(organizationScope, namespace, "wf-previous")
        assertThat(predecessorBefore?.instance?.get("status")).isEqualTo("completed")

        // The successor is a NEW workflowId whose relations link the sealed
        // predecessor; the predecessor is never reopened.
        val started = service.start(
            organizationScope,
            namespace,
            WorkflowStartCommand(
                workflowId = "wf-successor",
                workflowType = "wf-demo",
                title = "Successor of wf-previous",
                relations = linkedWorkflowRelations("wf-successor", "wf-previous"),
            ),
            ControllerExecutionInput(
                runtimeId = "agentos-primary",
                kind = "agentos",
                agentId = "runner",
                caseId = "case-successor",
            ),
        )

        assertThat(started.status).isEqualTo(201)
        val successor = repository.findInstance(organizationScope, namespace, "wf-successor")
        @Suppress("UNCHECKED_CAST")
        val relations = successor?.instance?.get("relations") as Map<String, Any?>
        assertThat(relations["previousWorkflowId"]).isEqualTo("wf-previous")
        assertThat(relations["rootWorkflowId"]).isEqualTo("wf-previous")
        assertThat(successor.instance["status"]).isEqualTo("ready")

        // The sealed predecessor is byte-for-byte untouched.
        val predecessorAfter = repository.findInstance(organizationScope, namespace, "wf-previous")
        assertThat(predecessorAfter?.revision).isEqualTo(predecessorBefore?.revision)
        assertThat(predecessorAfter?.instance?.get("status")).isEqualTo("completed")
    }

    // ------------------------------------------------------------------
    // Fixtures
    // ------------------------------------------------------------------

    private fun registerDefinition() {
        val raw = linkedMapOf<String, Any?>(
            "schemaVersion" to "1",
            "workflowType" to "wf-demo",
            "version" to "1.0.0",
            "title" to "Demo workflow",
            "steps" to listOf(
                linkedMapOf(
                    "id" to "gate",
                    "name" to "Gate",
                    "responsibility" to linkedMapOf("kind" to "human", "name" to "reviewer"),
                    "dependsOn" to emptyList<String>(),
                ),
            ),
        )
        val validated = WorkflowDefinitionValidator.validate(raw)
        check(validated is WorkflowDefinitionValidation.Valid) { "test definition must be valid: $validated" }
        val definition = validated.definition
        service.registerDefinition(
            organizationScope,
            WorkflowDefinitionRecord(
                workflowType = "wf-demo",
                version = "1.0.0",
                definitionHash = hashWorkflowDefinition(definition),
                definition = definition,
            ),
        )
    }

    private fun startWorkflow(workflowId: String): WorkflowHttpResult = service.start(
        organizationScope,
        namespace,
        WorkflowStartCommand(workflowId = workflowId, workflowType = "wf-demo", title = "Demo $workflowId"),
        ControllerExecutionInput(
            runtimeId = "agentos-primary",
            kind = "agentos",
            agentId = "runner",
            caseId = "case-$workflowId",
        ),
    )

    /** Drives the single human gate to its terminal status (`approve` → completed, `reject` → failed). */
    @Suppress("UNCHECKED_CAST")
    private fun driveHumanGate(workflowId: String, actionId: String) {
        val opened = service.openInteraction(
            organizationScope,
            namespace,
            workflowId,
            "gate",
            1,
            "Decide the gate?",
            listOf(
                mapOf("id" to "approve", "label" to "Approve"),
                mapOf("id" to "reject", "label" to "Reject"),
            ),
            "open-$workflowId",
        )
        val interaction = (opened.data as Map<String, Any?>)["interaction"] as Map<String, Any?>
        service.replyInteraction(
            organizationScope,
            namespace,
            workflowId,
            interaction["interactionId"] as String,
            2,
            actionId,
            "decision of $workflowId",
            "alice",
        )
    }

    /** CAS-fixture: forces the overall run status (only reachable via real transitions for completed/failed). */
    private fun forceRunStatus(workflowId: String, status: String) {
        val record = repository.findInstance(organizationScope, namespace, workflowId)!!
        val updated = repository.updateInstance(
            organizationScope,
            namespace,
            workflowId,
            record.revision,
            record.copy(
                revision = record.revision + 1,
                instance = record.instance + mapOf("status" to status, "revision" to record.revision + 1),
            ),
        )
        check(updated) { "fixture CAS must land" }
    }

    private fun insertWaitingInteraction(workflowId: String, interactionId: String) {
        interactionRepository.insert(
            organizationScope,
            HumanInteractionRecord(
                interactionId = interactionId,
                namespaceId = namespace,
                workflowId = workflowId,
                stepId = "gate",
                interactionType = "checkpoint",
                status = "waiting",
                revision = 1,
                payload = linkedMapOf(
                    "stepId" to "gate",
                    "prompt" to "Late checkpoint",
                    "actions" to listOf(mapOf("id" to "approve", "label" to "Approve")),
                ),
            ),
        )
    }

    private fun transitionRequest(workflowId: String, expectedRevision: Int): Map<String, Any?> = mapOf(
        "workflowId" to workflowId,
        "stepId" to "gate",
        "expectedRevision" to expectedRevision,
        "requestedStatus" to "running",
        "evidenceIds" to emptyList<String>(),
    )

    private fun execution(): WorkflowExecution = WorkflowExecution(
        kind = "factory-human",
        runtimeId = "factory-dashboard",
        actorId = "alice",
    )

    private fun runStatus(workflowId: String): String? =
        repository.findInstance(organizationScope, namespace, workflowId)?.instance?.get("status") as? String
}
