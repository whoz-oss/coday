package io.whozoss.factory.workflow

import io.whozoss.factory.Neo4jDomainIntegrationTest
import io.whozoss.factory.persistence.TenantScope
import io.whozoss.factory.workflow.domain.ControllerExecutionInput
import io.whozoss.factory.workflow.domain.WorkflowDefinitionRecord
import io.whozoss.factory.workflow.domain.WorkflowDefinitionValidation
import io.whozoss.factory.workflow.domain.WorkflowDefinitionValidator
import io.whozoss.factory.workflow.domain.WorkflowErrorCodes
import io.whozoss.factory.workflow.domain.WorkflowEvidenceItem
import io.whozoss.factory.workflow.domain.WorkflowException
import io.whozoss.factory.workflow.domain.WorkflowStartCommand
import io.whozoss.factory.workflow.domain.hashWorkflowDefinition
import io.whozoss.factory.workflow.persistence.WorkflowEvidenceRepository
import io.whozoss.factory.workflow.persistence.WorkflowRepository
import io.whozoss.factory.workflow.service.WorkflowService
import org.assertj.core.api.Assertions.assertThat
import org.assertj.core.api.Assertions.assertThatThrownBy
import org.junit.jupiter.api.Test
import org.springframework.beans.factory.annotation.Autowired

/**
 * Integration tests of the workflow aggregate against a real PostgreSQL
 * instance.
 *
 * Covers the declarative projection store's `expectedRevision` optimistic
 * locking, the append-only evidence log, the governed transition state machine,
 * and the atomic interaction -> evidence -> transition transaction (including
 * rollback on a stale revision).
 */
class WorkflowServiceIntegrationTest : Neo4jDomainIntegrationTest() {

    @Autowired
    private lateinit var service: WorkflowService

    @Autowired
    private lateinit var repository: WorkflowRepository

    @Autowired
    private lateinit var evidenceRepository: WorkflowEvidenceRepository

    private val organizationScope: TenantScope get() = scope
    private val namespace = "0d4bd471-df37-43d8-a8f7-c989f95e71d7"

    private fun registerDefinition(
        workflowType: String = "wf-demo",
        version: String = "1.0.0",
        steps: List<Map<String, Any?>> = listOf(
            linkedMapOf(
                "id" to "gate",
                "name" to "Gate",
                "responsibility" to linkedMapOf("kind" to "human", "name" to "reviewer"),
                "dependsOn" to emptyList<String>(),
            ),
        ),
    ) {
        val raw = linkedMapOf<String, Any?>(
            "schemaVersion" to "1",
            "workflowType" to workflowType,
            "version" to version,
            "title" to "Demo workflow",
            "steps" to steps,
        )
        val validated = WorkflowDefinitionValidator.validate(raw)
        check(validated is WorkflowDefinitionValidation.Valid) { "test definition must be valid: $validated" }
        val definition = validated.definition
        service.registerDefinition(
            organizationScope,
            WorkflowDefinitionRecord(
                workflowType = workflowType,
                version = version,
                definitionHash = hashWorkflowDefinition(definition),
                definition = definition,
            ),
        )
    }

    private fun startWorkflow(workflowId: String, workflowType: String = "wf-demo") = service.start(
        organizationScope,
        namespace,
        WorkflowStartCommand(workflowId = workflowId, workflowType = workflowType, title = "Demo $workflowId"),
        ControllerExecutionInput(
            runtimeId = "agentos-primary",
            kind = "agentos",
            agentId = "runner",
            caseId = "case-demo",
        ),
    )

    private fun projection(status: String) = linkedMapOf<String, Any?>(
        "schemaVersion" to "2",
        "workflowId" to "wf-optimistic",
        "workflowType" to "wf-demo",
        "title" to "Optimistic",
        "status" to status,
        "steps" to listOf(
            linkedMapOf(
                "id" to "gate",
                "name" to "Gate",
                "status" to status,
                "dependsOn" to emptyList<String>(),
                "responsibility" to linkedMapOf("kind" to "human", "name" to "reviewer"),
            ),
        ),
    )

    @Suppress("UNCHECKED_CAST")
    private fun data(result: io.whozoss.factory.workflow.service.WorkflowHttpResult): Map<String, Any?> =
        result.data as Map<String, Any?>

    // ------------------------------------------------------------------
    // Optimistic locking
    // ------------------------------------------------------------------

    @Test
    fun `projection publication honors expectedRevision and rejects a stale revision`() {
        val created = service.publishProjection(organizationScope, namespace, "wf-optimistic", projection("ready"), 0, null)
        assertThat(created.status).isEqualTo(201)
        assertThat(data(created)["revision"]).isEqualTo(1)

        val updated = service.publishProjection(organizationScope, namespace, "wf-optimistic", projection("running"), 1, null)
        assertThat(updated.status).isEqualTo(200)
        assertThat(data(updated)["revision"]).isEqualTo(2)

        assertThatThrownBy {
            service.publishProjection(organizationScope, namespace, "wf-optimistic", projection("blocked"), 1, null)
        }
            .isInstanceOf(WorkflowException::class.java)
            .extracting("errorCode")
            .isEqualTo(WorkflowErrorCodes.REVISION_CONFLICT)

        // The stale write must not have advanced the stored revision.
        assertThat(repository.findProjection(organizationScope, namespace, "wf-optimistic")?.revision).isEqualTo(2)
    }

    @Test
    fun `an identical publication is idempotent and does not advance the revision`() {
        service.publishProjection(organizationScope, namespace, "wf-optimistic", projection("ready"), 0, null)
        val replay = service.publishProjection(organizationScope, namespace, "wf-optimistic", projection("ready"), null, null)
        assertThat(data(replay)["changed"]).isEqualTo(false)
        assertThat(data(replay)["revision"]).isEqualTo(1)
    }

    @Test
    fun `publishing at a non-zero expectedRevision on an absent workflow conflicts`() {
        assertThatThrownBy {
            service.publishProjection(organizationScope, namespace, "wf-optimistic", projection("ready"), 5, null)
        }
            .isInstanceOf(WorkflowException::class.java)
            .extracting("errorCode")
            .isEqualTo(WorkflowErrorCodes.REVISION_CONFLICT)
    }

    // ------------------------------------------------------------------
    // Append-only evidence
    // ------------------------------------------------------------------

    @Test
    fun `evidence is append-only and an identical idempotent replay is a no-op`() {
        registerDefinition()
        startWorkflow("wf-evidence")

        val item = WorkflowEvidenceItem(
            evidenceId = "ev-1",
            namespaceId = namespace,
            workflowId = "wf-evidence",
            stepId = "gate",
            kind = "agent-result",
            outcome = "pass",
            source = mapOf("kind" to "factory-control-plane"),
            facts = emptyMap(),
            idempotencyKey = null,
            createdAt = null,
        )
        val first = service.appendEvidence(organizationScope, namespace, "wf-evidence", item)
        assertThat(first.status).isEqualTo(201)
        assertThat(evidenceRepository.list(organizationScope, namespace, "wf-evidence")).hasSize(1)

        // Replaying the same immutable id is accepted but adds no second row.
        val replay = service.appendEvidence(organizationScope, namespace, "wf-evidence", item)
        assertThat(replay.status).isEqualTo(200)
        assertThat(evidenceRepository.list(organizationScope, namespace, "wf-evidence")).hasSize(1)
    }

    // ------------------------------------------------------------------
    // Atomic interaction -> evidence -> transition
    // ------------------------------------------------------------------

    @Test
    @Suppress("UNCHECKED_CAST")
    fun `replying to a human interaction atomically records evidence and transitions`() {
        registerDefinition()
        startWorkflow("wf-atomic")

        val opened = service.openInteraction(
            organizationScope,
            namespace,
            "wf-atomic",
            "gate",
            1,
            "Approve the gate?",
            listOf(
                mapOf("id" to "approve", "label" to "Approve"),
                mapOf("id" to "reject", "label" to "Reject"),
            ),
            "open-1",
        )
        assertThat(opened.status).isEqualTo(201)
        val interaction = data(opened)["interaction"] as Map<String, Any?>
        val interactionId = interaction["interactionId"] as String
        assertThat(data(opened)["revision"]).isEqualTo(2)

        val replied = service.replyInteraction(
            organizationScope,
            namespace,
            "wf-atomic",
            interactionId,
            2,
            "approve",
            "looks good",
            "alice",
        )
        val replyData = data(replied)
        assertThat(replyData["revision"]).isEqualTo(3)
        assertThat(replyData["evidenceId"]).isNotNull
        assertThat(replyData["runtimeNotification"]).isEqualTo("not-configured")

        val evidence = evidenceRepository.list(organizationScope, namespace, "wf-atomic")
        assertThat(evidence).hasSize(1)
        assertThat(evidence.first().kind).isEqualTo("human-decision")
        assertThat(evidence.first().outcome).isEqualTo("pass")

        val projection = replyData["projection"] as Map<String, Any?>
        assertThat(projection["status"]).isEqualTo("completed")
        assertThat(repository.findInstance(organizationScope, namespace, "wf-atomic")?.revision).isEqualTo(3)
    }

    @Test
    fun `a stale reply rolls the whole transaction back leaving no evidence`() {
        registerDefinition()
        startWorkflow("wf-atomic-rollback")

        val opened = service.openInteraction(
            organizationScope,
            namespace,
            "wf-atomic-rollback",
            "gate",
            1,
            "Approve the gate?",
            listOf(
                mapOf("id" to "approve", "label" to "Approve"),
                mapOf("id" to "reject", "label" to "Reject"),
            ),
            "open-2",
        )
        @Suppress("UNCHECKED_CAST")
        val interactionId = ((opened.data as Map<String, Any?>)["interaction"] as Map<String, Any?>)["interactionId"] as String

        assertThatThrownBy {
            service.replyInteraction(organizationScope, namespace, "wf-atomic-rollback", interactionId, 1, "approve", null, "bob")
        }
            .isInstanceOf(WorkflowException::class.java)
            .extracting("errorCode")
            .isEqualTo(WorkflowErrorCodes.REVISION_CONFLICT)

        // The rejected reply must not have appended the human-decision evidence.
        assertThat(evidenceRepository.list(organizationScope, namespace, "wf-atomic-rollback")).isEmpty()
        assertThat(repository.findInstance(organizationScope, namespace, "wf-atomic-rollback")?.revision).isEqualTo(2)
    }
}
