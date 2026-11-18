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
import io.whozoss.factory.workflow.persistence.HumanInteractionRepository
import io.whozoss.factory.workflow.service.WorkflowService
import io.whozoss.factory.workflow.sse.WorkflowSseHub
import io.whozoss.factory.sdk.spi.FactoryHumanCheckpointPolicy
import io.whozoss.factory.sdk.spi.HumanCheckpointContext
import io.whozoss.factory.sdk.spi.HumanCheckpointVerdict
import io.mockk.every
import io.mockk.mockk
import org.assertj.core.api.Assertions.assertThat
import org.assertj.core.api.Assertions.assertThatThrownBy
import org.junit.jupiter.api.Test
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.transaction.PlatformTransactionManager
import org.pf4j.PluginManager

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

    @Autowired
    private lateinit var interactionRepository: HumanInteractionRepository

    @Autowired
    private lateinit var sseHub: WorkflowSseHub

    @Autowired
    private lateinit var transactionManager: PlatformTransactionManager

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
    fun `generic human reply has no Forge gate role authorization`() {
        registerDefinition(workflowType = "forge.delivery")
        startWorkflow("wf-forge-role-gap", workflowType = "forge.delivery")
        val opened = service.openInteraction(
            organizationScope,
            namespace,
            "wf-forge-role-gap",
            "gate",
            1,
            "Approve the packet?",
            listOf(
                mapOf("id" to "approve", "label" to "Approve"),
                mapOf("id" to "reject", "label" to "Reject"),
            ),
            "open-role-gap",
        )
        val interactionId = (data(opened)["interaction"] as Map<String, Any?>)["interactionId"] as String

        // The service receives an actor ID but no trusted role/entitlements.
        // This succeeds today and demonstrates the missing policy hook.
        val replied = service.replyInteraction(
            organizationScope, namespace, "wf-forge-role-gap", interactionId, 2,
            "approve", null, "unassigned-member",
        )
        assertThat(data(replied)["evidenceId"]).isNotNull
        val evidence = evidenceRepository.list(organizationScope, namespace, "wf-forge-role-gap").single()
        assertThat(evidence.source?.get("actorId")).isEqualTo("unassigned-member")
        assertThat(evidence.facts["decidedAt"]).isInstanceOf(String::class.java)
        assertThat(evidence.facts).doesNotContainKey("packetArtifactId")
        assertThat(evidence.facts).doesNotContainKey("candidateGeneration")
    }

    @Test
    @Suppress("UNCHECKED_CAST")
    fun `opted-in human reply audits exact packet and refuses a stale replacement`() {
        val packet = mapOf("repository" to "whoz", "commit" to "a".repeat(40), "manifestSha256" to "b".repeat(64))
        val policy = object : FactoryHumanCheckpointPolicy {
            override fun supports(flowType: String, stepId: String) =
                flowType == "forge.delivery" && stepId == "gate"

            override fun decide(context: HumanCheckpointContext, presented: Map<String, Any?>, actionId: String) =
                if (context.run.domainState["approvedPacketCandidate"] == presented)
                    HumanCheckpointVerdict.Allowed(presented + ("gate" to "packet-approval"))
                else HumanCheckpointVerdict.Denied("PACKET_DECISION_STALE")
        }
        val plugins = mockk<PluginManager>()
        every { plugins.getExtensions(FactoryHumanCheckpointPolicy::class.java) } returns listOf(policy)
        val governed = WorkflowService(repository, evidenceRepository, interactionRepository, sseHub,
            transactionManager = transactionManager, pluginManager = plugins)
        registerDefinition(workflowType = "forge.delivery")

        fun open(workflowId: String): String {
            startWorkflow(workflowId, workflowType = "forge.delivery")
            val result = service.openInteraction(organizationScope, namespace, workflowId, "gate", 1,
                "Approve the packet?", listOf(mapOf("id" to "approve", "label" to "Approve"),
                    mapOf("id" to "reject", "label" to "Reject")), "open-$workflowId")
            val interactionId = (data(result)["interaction"] as Map<String, Any?>)["interactionId"] as String
            val instance = repository.findInstance(organizationScope, namespace, workflowId)!!
            val updated = instance.copy(revision = instance.revision + 1,
                instance = instance.instance + mapOf("revision" to instance.revision + 1,
                    "execution" to mapOf("plugin" to "forge", "humanCheckpointPolicyRequired" to true)))
            assertThat(repository.updateInstance(organizationScope, namespace, workflowId, instance.revision, updated)).isTrue()
            val interaction = interactionRepository.find(organizationScope, namespace, workflowId, interactionId)!!
            assertThat(interactionRepository.update(organizationScope, namespace, workflowId, interactionId,
                interaction.revision, interaction.copy(revision = interaction.revision + 1,
                    payload = interaction.payload + ("presented" to packet)))).isTrue()
            evidenceRepository.append(organizationScope, namespace, workflowId, WorkflowEvidenceItem(
                evidenceId = "packet-$workflowId", namespaceId = namespace, workflowId = workflowId,
                stepId = "gate", kind = "agent-result", outcome = "pass",
                source = mapOf("kind" to "factory-control-plane"),
                facts = mapOf("acceptedDomainState" to mapOf("approvedPacketCandidate" to packet)),
                idempotencyKey = null, createdAt = null,
            ))
            return interactionId
        }

        val currentId = open("wf-packet-current")
        val approved = governed.replyInteraction(organizationScope, namespace, "wf-packet-current", currentId,
            3, "approve", null, "authenticated-member")
        assertThat(data(approved)["evidenceId"]).isNotNull
        val decision = evidenceRepository.list(organizationScope, namespace, "wf-packet-current")
            .single { it.kind == "human-decision" }
        assertThat(decision.source?.get("actorId")).isEqualTo("authenticated-member")
        assertThat(decision.facts).containsEntry("repository", "whoz")
            .containsEntry("commit", "a".repeat(40))
            .containsEntry("manifestSha256", "b".repeat(64))
        assertThat(decision.facts["decidedAt"]).isInstanceOf(String::class.java)

        val staleId = open("wf-packet-stale")
        evidenceRepository.append(organizationScope, namespace, "wf-packet-stale", WorkflowEvidenceItem(
            evidenceId = "replacement-packet", namespaceId = namespace, workflowId = "wf-packet-stale",
            stepId = "gate", kind = "agent-result", outcome = "pass",
            source = mapOf("kind" to "factory-control-plane"),
            facts = mapOf("acceptedDomainState" to mapOf("approvedPacketCandidate" to
                (packet + ("commit" to "c".repeat(40))))), idempotencyKey = null, createdAt = null,
        ))
        assertThatThrownBy {
            governed.replyInteraction(organizationScope, namespace, "wf-packet-stale", staleId,
                3, "approve", null, "authenticated-member")
        }.isInstanceOf(WorkflowException::class.java)
            .extracting("errorCode").isEqualTo(WorkflowErrorCodes.INVALID_REPLY)
        assertThat(evidenceRepository.list(organizationScope, namespace, "wf-packet-stale"))
            .noneMatch { it.kind == "human-decision" }
    }

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
