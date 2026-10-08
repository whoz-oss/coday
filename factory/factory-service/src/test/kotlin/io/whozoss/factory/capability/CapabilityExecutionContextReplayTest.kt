package io.whozoss.factory.capability

import io.whozoss.factory.Neo4jDomainIntegrationTest
import io.whozoss.factory.adapter.agentos.AgentOsExecutionAdapter
import io.whozoss.factory.adapter.agentos.AgentOsExecutionVerdict
import io.whozoss.factory.adapter.agentos.CaseHandle
import io.whozoss.factory.agentattempt.domain.AgentAttemptStatus
import io.whozoss.factory.agentattempt.domain.AttemptContextEnvelope
import io.whozoss.factory.agentattempt.domain.CanonicalJsonHash
import io.whozoss.factory.agentattempt.domain.DurableAgentAttempt
import io.whozoss.factory.agentattempt.persistence.AgentStepAttemptRepository
import io.whozoss.factory.agentattempt.service.DurableAgentAttemptService
import io.whozoss.factory.workflow.domain.ControllerExecutionInput
import io.whozoss.factory.workflow.domain.ResponsibilityKind
import io.whozoss.factory.workflow.domain.WorkflowDefinitionRecord
import io.whozoss.factory.workflow.domain.WorkflowDefinitionValidation
import io.whozoss.factory.workflow.domain.WorkflowDefinitionValidator
import io.whozoss.factory.workflow.domain.WorkflowStartCommand
import io.whozoss.factory.workflow.domain.WorkflowStepDefinition
import io.whozoss.factory.workflow.domain.WorkflowStepResponsibility
import io.whozoss.factory.workflow.domain.hashWorkflowDefinition
import io.whozoss.factory.workflow.persistence.HumanInteractionRepository
import io.whozoss.factory.workflow.persistence.WorkflowEvidenceRepository
import io.whozoss.factory.workflow.persistence.WorkflowRepository
import io.whozoss.factory.workflow.service.WorkflowService
import java.nio.file.Path
import java.util.UUID
import java.util.concurrent.CopyOnWriteArrayList
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.transaction.PlatformTransactionManager

/**
 * Lot D — per-attempt frozen context envelope and replay integrity.
 *
 * Drives the real durable AgentOS bridge (Spring beans + a stateful fake
 * adapter): a brand-new attempt freezes and persists its context envelope and
 * brief, while a replayed/adopted attempt reuses the frozen context verbatim
 * instead of re-deriving it from the current durable inputs.
 */
class CapabilityExecutionContextReplayTest : Neo4jDomainIntegrationTest() {

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
    private lateinit var transactionManager: PlatformTransactionManager

    @TempDir
    lateinit var repoRoot: Path

    private val namespace = "2b6f8d2f-8d1a-4f0e-9b6e-context-envelope"

    private fun agentStep(id: String, dependsOn: List<String> = emptyList()): WorkflowStepDefinition =
        WorkflowStepDefinition(id, "Step $id", WorkflowStepResponsibility(ResponsibilityKind.AGENT, "architect"), dependsOn)

    private fun startSession(workflowType: String, workflowId: String) {
        val raw = linkedMapOf<String, Any?>(
            "schemaVersion" to "1",
            "workflowType" to workflowType,
            "version" to "1.0.0",
            "title" to "Session $workflowType",
            "steps" to listOf(
                linkedMapOf<String, Any?>(
                    "id" to "A",
                    "name" to "Step A",
                    "responsibility" to mapOf("kind" to "agent", "name" to "architect"),
                    "dependsOn" to emptyList<String>(),
                ),
            ),
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

    private fun service(adapter: AgentOsExecutionAdapter): CapabilityExecutionService =
        CapabilityExecutionService(
            CapabilityResolver(),
            workflowRepository,
            evidenceRepository,
            interactionRepository,
            attemptRepository,
            durableAgentAttemptService = durableAgentAttemptService,
            agentOsExecutionAdapter = adapter,
            transactionManager = transactionManager,
        )

    /** Stateful fake adapter capturing the exact brief handed to the first turn. */
    private class BriefCapturingAdapter : AgentOsExecutionAdapter {
        val startTurnBriefs = CopyOnWriteArrayList<String>()

        override fun createOrRecoverExecution(
            namespaceId: String,
            workflowId: String,
            stepId: String,
            externalUserId: String?,
            attemptId: String,
            capabilityToken: String?,
            caseId: String,
        ): CaseHandle = CaseHandle(caseId = caseId, namespaceId = namespaceId, recovered = false)

        override fun startTurn(
            caseId: String,
            persona: String,
            brief: String,
            externalUserId: String?,
            attemptId: String,
            capabilityToken: String?,
        ) {
            startTurnBriefs.add(brief)
        }

        override fun observeTurn(caseId: String, attemptId: String, timeoutMs: Long): AgentOsExecutionVerdict =
            AgentOsExecutionVerdict.Succeeded(emptyMap())

        override fun reconcile(caseId: String): AgentOsExecutionVerdict =
            AgentOsExecutionVerdict.Succeeded(emptyMap())

        override fun interrupt(caseId: String, reason: String) = Unit

        override fun kill(caseId: String) = Unit
    }

    @Test
    fun `a fresh attempt freezes its context envelope and the brief it pins`() {
        val workflowId = "wf-envelope-fresh-${UUID.randomUUID()}"
        startSession("ctx-envelope-fresh", workflowId)
        val adapter = BriefCapturingAdapter()

        val result = service(adapter).resolveAndRecord(scope, namespace, workflowId, agentStep("A"), repoRoot)

        assertThat(result.outcome).isInstanceOf(CapabilityOutcome.AgentCompleted::class.java)
        val attemptId = CapabilityExecutionService.stableAttemptId(workflowId, "A")
        val persisted = durableAgentAttemptService.find(scope, namespace, workflowId, "A", attemptId)!!
        assertThat(persisted.contextEnvelope).isNotNull
        val envelope = AttemptContextEnvelope.fromJson(persisted.contextEnvelope!!)
        assertThat(envelope.workstreamId).isEqualTo(scope.workstreamId)
        assertThat(envelope.namespaceId).isEqualTo(namespace)
        assertThat(envelope.workflowId).isEqualTo(workflowId)
        assertThat(envelope.stepId).isEqualTo("A")
        assertThat(envelope.agentName).isEqualTo("architect")
        // The frozen envelope pins exactly the brief handed to the turn.
        assertThat(adapter.startTurnBriefs).hasSize(1)
        assertThat(envelope.briefHash).isEqualTo(CanonicalJsonHash.sha256(adapter.startTurnBriefs.single()))
    }

    @Test
    fun `a replay reuses the frozen context envelope verbatim instead of rebuilding it`() {
        val workflowId = "wf-envelope-replay-${UUID.randomUUID()}"
        startSession("ctx-envelope-replay", workflowId)
        val attemptId = CapabilityExecutionService.stableAttemptId(workflowId, "A")
        val frozenBrief = "FROZEN BRIEF — never re-derived"
        val frozenEnvelope = AttemptContextEnvelope(
            organizationId = scope.organizationId,
            workstreamId = scope.workstreamId,
            namespaceId = namespace,
            workflowId = workflowId,
            stepId = "A",
            attemptNumber = 1,
            agentName = "architect",
            briefHash = CanonicalJsonHash.sha256(frozenBrief),
        )
        val frozenJson = frozenEnvelope.toJson()
        durableAgentAttemptService.register(
            scope,
            DurableAgentAttempt(
                attemptId = attemptId,
                caseId = "case-frozen",
                namespaceId = namespace,
                workflowId = workflowId,
                stepId = "A",
                attemptNumber = 1,
                agentName = "architect",
                brief = frozenBrief,
                contextEnvelope = frozenJson,
            ),
        )

        val adapter = BriefCapturingAdapter()
        val result = service(adapter).resolveAndRecord(scope, namespace, workflowId, agentStep("A"), repoRoot)

        assertThat(result.outcome).isInstanceOf(CapabilityOutcome.AgentCompleted::class.java)
        // The turn received the FROZEN brief, not one re-derived from evidence.
        assertThat(adapter.startTurnBriefs).containsExactly(frozenBrief)
        val persisted = durableAgentAttemptService.find(scope, namespace, workflowId, "A", attemptId)!!
        assertThat(persisted.status).isEqualTo(AgentAttemptStatus.SUCCEEDED)
        assertThat(persisted.brief).isEqualTo(frozenBrief)
        assertThat(persisted.contextEnvelope).isEqualTo(frozenJson)
    }
}
