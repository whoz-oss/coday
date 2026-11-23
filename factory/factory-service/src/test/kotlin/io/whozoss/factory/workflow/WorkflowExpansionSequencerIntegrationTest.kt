package io.whozoss.factory.workflow

import com.fasterxml.jackson.databind.JsonNode
import com.fasterxml.jackson.databind.ObjectMapper
import io.mockk.every
import io.mockk.mockk
import io.whozoss.factory.Neo4jDomainIntegrationTest
import io.whozoss.factory.agentattempt.domain.AgentStepAttemptRecord
import io.whozoss.factory.agentattempt.domain.AgentStepResultCapabilityIdentity
import io.whozoss.factory.agentattempt.domain.AgentStepResultObservedIdentity
import io.whozoss.factory.agentattempt.domain.DurableAgentAttempt
import io.whozoss.factory.agentattempt.persistence.AgentStepAttemptRepository
import io.whozoss.factory.agentattempt.service.AgentStepResultService
import io.whozoss.factory.agentattempt.service.DurableAgentAttemptService
import io.whozoss.factory.capability.CapabilityExecution
import io.whozoss.factory.capability.CapabilityExecutionService
import io.whozoss.factory.capability.CapabilityOutcome
import io.whozoss.factory.workflow.domain.ControllerExecutionInput
import io.whozoss.factory.workflow.domain.HumanInteractionRecord
import io.whozoss.factory.workflow.domain.ResponsibilityKind
import io.whozoss.factory.workflow.domain.WorkflowDefinitionInput
import io.whozoss.factory.workflow.domain.WorkflowInstanceRecord
import io.whozoss.factory.workflow.domain.WorkflowStartCommand
import io.whozoss.factory.workflow.domain.WorkflowStatuses
import io.whozoss.factory.workflow.domain.WorkflowStepDefinition
import io.whozoss.factory.workflow.domain.WorkflowStepStateRecord
import io.whozoss.factory.workflow.domain.createWorkflowInstance
import io.whozoss.factory.workflow.persistence.HumanInteractionRepository
import io.whozoss.factory.workflow.persistence.WorkflowEvidenceRepository
import io.whozoss.factory.workflow.persistence.WorkflowRepository
import io.whozoss.factory.workflow.service.SessionDefinitionCatalog
import io.whozoss.factory.workflow.service.SessionRunService
import io.whozoss.factory.workflow.service.WorkflowExpansionService
import io.whozoss.factory.workflow.service.WorkflowService
import io.whozoss.factory.workflow.service.toPolicyDefinition
import io.whozoss.factory.workflow.sse.WorkflowSseHub
import java.nio.file.Path
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.transaction.PlatformTransactionManager

/**
 * The riskiest part of the change: the session sequencer driving a run THROUGH an expansion. The capability
 * execution is deterministic (no AgentOS); persistence, the result channel, the resolver and the sequencer loop
 * are the real beans.
 */
class WorkflowExpansionSequencerIntegrationTest : Neo4jDomainIntegrationTest() {

    @Autowired private lateinit var repository: WorkflowRepository
    @Autowired private lateinit var evidence: WorkflowEvidenceRepository
    @Autowired private lateinit var interactions: HumanInteractionRepository
    @Autowired private lateinit var hub: WorkflowSseHub
    @Autowired private lateinit var transactions: PlatformTransactionManager
    @Autowired private lateinit var results: AgentStepResultService
    @Autowired private lateinit var legacyAttempts: AgentStepAttemptRepository
    @Autowired private lateinit var durableAttempts: DurableAgentAttemptService
    @Autowired private lateinit var expansion: WorkflowExpansionService
    @Autowired private lateinit var catalog: SessionDefinitionCatalog
    @Autowired private lateinit var objectMapper: ObjectMapper

    private val namespace = "expansion-seq-ns"
    private val agent = "Archay"
    private val expander = "architect-plan"

    private fun startRun(workflowId: String): WorkflowInstanceRecord {
        val record = catalog.load().single { it.workflowType == "architect-custom-flow" }
        repository.saveDefinition(scope, record)
        val input = WorkflowDefinitionInput(record.workflowType, record.version, record.definitionHash, record.toPolicyDefinition().steps)
        val created = createWorkflowInstance(
            WorkflowStartCommand(workflowId, record.workflowType, "Expansion run"),
            input,
            ControllerExecutionInput("test", "agentos", "runner"),
        )
        return repository.insertInstance(
            scope,
            WorkflowInstanceRecord(namespace, workflowId, 1, "active", created.creationCommandHash, created.instance, created.projection),
        )
    }

    /** The expanding step's PASS carrying [continuation], accepted by the real result channel. Returns its attempt id. */
    private fun submitExpandingResult(workflowId: String, continuation: String): String {
        val attemptId = "attempt-$workflowId"
        legacyAttempts.insert(scope, AgentStepAttemptRecord(namespace, workflowId, expander, attemptId, "agent-1", "running", 1, "{}"))
        val token = results.issue(
            scope,
            AgentStepResultCapabilityIdentity(attemptId, workflowId, expander, namespace, "case-$workflowId", agent, "sha256:${"b".repeat(64)}"),
        ).token
        val business: JsonNode = objectMapper.readTree(
            """{"status":"PASS","summary":"plan done","claims":{"modifiedFiles":[]},"continuation":$continuation}""",
        )
        results.submit(scope, token, business, AgentStepResultObservedIdentity(attemptId, "case-$workflowId", agent, namespace), null)
        durableAttempts.register(
            scope,
            DurableAgentAttempt(attemptId, "case-$workflowId", namespace, workflowId, expander, 1, agent),
        )
        return attemptId
    }

    private fun agentStep(id: String, dependsOn: String = "[]") =
        """{"id":"$id","name":"Step $id","responsibility":{"kind":"agent","name":"$agent"},"dependsOn":$dependsOn}"""

    private fun humanStep(id: String, dependsOn: String) =
        """{"id":"$id","name":"Step $id","responsibility":{"kind":"human","name":"Lead"},"dependsOn":$dependsOn}"""

    /** Deterministic capability: records the executed steps, completes agents, opens a checkpoint for humans. */
    private fun capability(workflowId: String, expanderAttemptId: String, called: MutableList<String>): CapabilityExecutionService {
        val capability = mockk<CapabilityExecutionService>()
        every { capability.hasFinalizedAttempt(any(), any(), any(), any()) } returns false
        every { capability.canResumeWaitingAgentStep(any(), any(), any(), any()) } returns false
        every { capability.hasAcceptedFinalResult(any(), any(), any(), any()) } returns false
        every { capability.resolveAndRecord(any(), any(), any(), any(), any(), any(), any()) } answers {
            val step = invocation.args[3] as WorkflowStepDefinition
            called += step.id
            if (step.responsibility.kind == ResponsibilityKind.HUMAN) {
                val interactionId = "interaction-${step.id}"
                interactions.insert(
                    scope,
                    HumanInteractionRecord(
                        interactionId, namespace, workflowId, step.id, "checkpoint", "waiting", 1,
                        linkedMapOf(
                            "stepId" to step.id,
                            "role" to step.responsibility.name,
                            "actions" to listOf(mapOf("id" to "approve", "label" to "Approve"), mapOf("id" to "reject", "label" to "Reject")),
                        ),
                    ),
                )
                CapabilityExecution(CapabilityOutcome.HumanCheckpointRequired(step.id, step.responsibility.name), interactionId = interactionId)
            } else {
                CapabilityExecution(
                    CapabilityOutcome.AgentCompleted(step.id, step.responsibility.name, "PASS", emptyMap()),
                    attemptId = if (step.id == expander) expanderAttemptId else "attempt-${step.id}",
                )
            }
        }
        return capability
    }

    private fun runner(capability: CapabilityExecutionService) =
        SessionRunService(repository, evidence, interactions, capability, hub, transactionManager = transactions, expansionService = expansion)

    private fun stepStatuses(workflowId: String) =
        repository.findStepStates(scope, namespace, workflowId).associate { it.stepId to it.status }

    @Test
    fun `the sequencer adds the steps after the expanding step, runs them in order and a human checkpoint of the continuation can be answered`() {
        val workflowId = "expansion-seq-human"
        startRun(workflowId)
        val definitionsBefore = repository.listDefinitions(scope).map { it.workflowType to it.definitionHash }
        val continuation = """{"steps":[${agentStep("build")},${humanStep("approve", "[\"build\"]")},${agentStep("ship", "[\"approve\"]")}]}"""
        val attemptId = submitExpandingResult(workflowId, continuation)
        val called = mutableListOf<String>()
        val runner = runner(capability(workflowId, attemptId, called))

        val first = runner.runSession(scope, namespace, workflowId, Path.of("."))

        assertThat(first.status).isEqualTo(WorkflowStatuses.WAITING_HUMAN)
        assertThat(called).containsExactly(expander, "build", "approve")
        assertThat(stepStatuses(workflowId)).containsEntry(expander, WorkflowStatuses.COMPLETED)
            .containsEntry("build", WorkflowStatuses.COMPLETED)
            .containsEntry("approve", WorkflowStatuses.WAITING_HUMAN)
            .containsEntry("ship", WorkflowStatuses.PENDING)
        val projected = (repository.findInstance(scope, namespace, workflowId)!!.projection["steps"] as List<*>)
            .filterIsInstance<Map<*, *>>().map { it["id"] }
        assertThat(projected).containsExactly(expander, "build", "approve", "ship")

        // The human reply goes through WorkflowService, which must also follow the effective definition.
        val workflowService = WorkflowService(repository, evidence, interactions, hub, sessionRunService = runner, transactionManager = transactions)
        workflowService.replyInteraction(scope, namespace, workflowId, "interaction-approve", 1, "approve", null, "alice", Path.of("."))

        assertThat(called).containsExactly(expander, "build", "approve", "ship")
        assertThat(stepStatuses(workflowId).values).containsOnly(WorkflowStatuses.COMPLETED)
        assertThat(repository.findInstance(scope, namespace, workflowId)!!.projection["status"]).isEqualTo(WorkflowStatuses.COMPLETED)
        assertThat(repository.listDefinitions(scope).map { it.workflowType to it.definitionHash }).isEqualTo(definitionsBefore)
    }

    @Test
    fun `a crash between the finalization of the expanding step and the addition of its steps is recovered by the next pass`() {
        val workflowId = "expansion-seq-crash"
        startRun(workflowId)
        val attemptId = submitExpandingResult(workflowId, """{"steps":[${agentStep("build")},${agentStep("review", "[\"build\"]")}]}""")
        // The expanding step was finalized (COMPLETED) but the process died before its steps were added.
        repository.upsertStepState(
            scope,
            WorkflowStepStateRecord(
                namespace, workflowId, expander, 1, WorkflowStatuses.COMPLETED,
                mapOf("startedAt" to "2026-01-01T00:00:00Z", "completedAt" to "2026-01-01T00:01:00Z"),
            ),
        )
        assertThat(repository.findInstance(scope, namespace, workflowId)!!.instance["expansion"]).isNull()
        val called = mutableListOf<String>()
        val runner = runner(capability(workflowId, attemptId, called))

        val result = runner.runSession(scope, namespace, workflowId, Path.of("."))

        assertThat(result.status).isEqualTo(WorkflowStatuses.COMPLETED)
        assertThat(called).containsExactly("build", "review")
        assertThat(evidence.list(scope, namespace, workflowId).filter { it.kind == WorkflowExpansionService.EVIDENCE_KIND }).hasSize(1)

        // A replay runs nothing again and adds nothing again.
        runner.runSession(scope, namespace, workflowId, Path.of("."))
        assertThat(called).containsExactly("build", "review")
        assertThat(evidence.list(scope, namespace, workflowId).filter { it.kind == WorkflowExpansionService.EVIDENCE_KIND }).hasSize(1)
    }

    @Test
    fun `a run whose expanding step failed adds no steps`() {
        val workflowId = "expansion-seq-failed"
        startRun(workflowId)
        repository.upsertStepState(
            scope,
            WorkflowStepStateRecord(namespace, workflowId, expander, 1, WorkflowStatuses.FAILED, emptyMap()),
        )
        val called = mutableListOf<String>()

        val result = runner(capability(workflowId, "none", called)).runSession(scope, namespace, workflowId, Path.of("."))

        assertThat(result.status).isEqualTo(WorkflowStatuses.FAILED)
        assertThat(called).isEmpty()
        assertThat(repository.findInstance(scope, namespace, workflowId)!!.instance["expansion"]).isNull()
    }
}
