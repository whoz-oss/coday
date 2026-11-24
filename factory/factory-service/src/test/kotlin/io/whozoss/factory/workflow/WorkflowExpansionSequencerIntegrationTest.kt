package io.whozoss.factory.workflow

import com.fasterxml.jackson.databind.JsonNode
import com.fasterxml.jackson.databind.ObjectMapper
import io.mockk.every
import io.mockk.mockk
import io.whozoss.factory.Neo4jDomainIntegrationTest
import io.whozoss.factory.agentattempt.domain.AgentStepAttemptRecord
import io.whozoss.factory.agentattempt.domain.AgentStepResultCapabilityIdentity
import io.whozoss.factory.agentattempt.domain.AgentStepResultObservedIdentity
import io.whozoss.factory.agentattempt.persistence.AgentStepAttemptRepository
import io.whozoss.factory.agentattempt.service.AgentStepResultService
import io.whozoss.factory.capability.CapabilityExecution
import io.whozoss.factory.capability.CapabilityExecutionService
import io.whozoss.factory.capability.CapabilityOutcome
import io.whozoss.factory.persistence.TenantScope
import io.whozoss.factory.workflow.domain.ControllerExecutionInput
import io.whozoss.factory.workflow.domain.HumanInteractionRecord
import io.whozoss.factory.workflow.domain.ResponsibilityKind
import io.whozoss.factory.workflow.domain.WorkflowDefinitionInput
import io.whozoss.factory.workflow.domain.WorkflowDefinitionRecord
import io.whozoss.factory.workflow.domain.WorkflowDefinitionValidation
import io.whozoss.factory.workflow.domain.WorkflowDefinitionValidator
import io.whozoss.factory.workflow.domain.hashWorkflowDefinition
import io.whozoss.factory.workflow.domain.WorkflowInstanceRecord
import io.whozoss.factory.workflow.domain.WorkflowStartCommand
import io.whozoss.factory.workflow.domain.WorkflowStatuses
import io.whozoss.factory.workflow.domain.WorkflowStepDefinition
import io.whozoss.factory.workflow.domain.WorkflowTransitionRequest
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
import io.whozoss.factory.workspace.WorkspaceContextPort
import io.whozoss.factory.workspace.WorkspacePrecheckException
import io.whozoss.factory.workspace.WorkspacePrecheckService
import io.whozoss.factory.workspace.WorkspaceProperties
import java.nio.file.Path
import org.assertj.core.api.Assertions.assertThat
import org.assertj.core.api.Assertions.assertThatThrownBy
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.context.annotation.Import
import org.springframework.transaction.PlatformTransactionManager

/**
 * The riskiest part of the change: the session sequencer driving a run THROUGH an expansion. The capability
 * execution is deterministic (no AgentOS); persistence, the result channel, the resolver and the sequencer loop
 * are the real beans.
 *
 * What the tests pin (the decisions of the review plan, without a forced human approval):
 *  - the steps are added in the same transaction that completes the expanding step, and run right after it;
 *  - a failure of the finalization adds nothing and fails the step (no state to reconcile later);
 *  - a human step written by the Architect suspends the run like any human step, and a reject blocks its dependants;
 *  - the workspace precheck runs again on the full list of steps before the first added step.
 */
@Import(ExpansionPrecheckPortConfiguration::class)
class WorkflowExpansionSequencerIntegrationTest : Neo4jDomainIntegrationTest() {

    @Autowired private lateinit var repository: WorkflowRepository
    @Autowired private lateinit var evidence: WorkflowEvidenceRepository
    @Autowired private lateinit var interactions: HumanInteractionRepository
    @Autowired private lateinit var hub: WorkflowSseHub
    @Autowired private lateinit var transactions: PlatformTransactionManager
    @Autowired private lateinit var results: AgentStepResultService
    @Autowired private lateinit var legacyAttempts: AgentStepAttemptRepository
    @Autowired private lateinit var expansion: WorkflowExpansionService
    @Autowired private lateinit var catalog: SessionDefinitionCatalog
    @Autowired private lateinit var objectMapper: ObjectMapper
    @Autowired private lateinit var agents: MutableAgentList

    private val namespace = "expansion-seq-ns"
    private val agent = "Archay"
    private val expander = "architect-plan"

    @BeforeEach
    fun resetAgents() = agents.reset()

    private fun architectDefinition(): WorkflowDefinitionRecord =
        catalog.load().single { it.workflowType == "architect-custom-flow" }

    /** A starting workflow of another circle: its own type, its own step id and its own agent. */
    private fun circleDefinition(workflowType: String, stepId: String, agentName: String): WorkflowDefinitionRecord {
        val raw = linkedMapOf<String, Any?>(
            "schemaVersion" to "1",
            "workflowType" to workflowType,
            "version" to "1.0.0",
            "title" to "Business analysis run designed by the $agentName",
            "steps" to listOf(
                linkedMapOf<String, Any?>(
                    "id" to stepId,
                    "name" to "Analyse the request and design the steps of the run",
                    "responsibility" to linkedMapOf("kind" to "agent", "name" to agentName),
                    "dependsOn" to emptyList<String>(),
                    "entry" to listOf("Read the request and the ticket given at launch"),
                    "mission" to "Analyse the request, design the steps that deliver it and send them as the continuation.",
                    "exit" to listOf("Submit PASS with the continuation"),
                    "expansion" to linkedMapOf("mode" to "append"),
                ),
            ),
        )
        val validated = WorkflowDefinitionValidator.validate(raw) as WorkflowDefinitionValidation.Valid
        return WorkflowDefinitionRecord(workflowType, "1.0.0", hashWorkflowDefinition(validated.definition), validated.definition)
    }

    private fun startRun(workflowId: String, record: WorkflowDefinitionRecord = architectDefinition()): WorkflowInstanceRecord {
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
    private fun submitExpandingResult(
        workflowId: String,
        continuation: String,
        expanderId: String = expander,
        agentName: String = agent,
    ): String {
        val attemptId = "attempt-$workflowId"
        legacyAttempts.insert(scope, AgentStepAttemptRecord(namespace, workflowId, expanderId, attemptId, "agent-1", "running", 1, "{}"))
        val token = results.issue(
            scope,
            AgentStepResultCapabilityIdentity(attemptId, workflowId, expanderId, namespace, "case-$workflowId", agentName, "sha256:${"b".repeat(64)}"),
        ).token
        val business: JsonNode = objectMapper.readTree(
            """{"status":"PASS","summary":"plan done","claims":{"modifiedFiles":[]},"continuation":$continuation}""",
        )
        results.submit(scope, token, business, AgentStepResultObservedIdentity(attemptId, "case-$workflowId", agentName, namespace), null)
        return attemptId
    }

    private fun agentStep(id: String, dependsOn: String = "[]", name: String = agent) =
        """{"id":"$id","name":"Step $id","responsibility":{"kind":"agent","name":"$name"},"dependsOn":$dependsOn}"""

    private fun humanStep(id: String, dependsOn: String = "[]") =
        """{"id":"$id","name":"Step $id","responsibility":{"kind":"human","name":"Lead"},"dependsOn":$dependsOn}"""

    private val twoSteps get() = """{"steps":[${agentStep("build")},${agentStep("review", "[\"build\"]")}]}"""

    /** A human gate written by the Architect, then a step that depends on it. */
    private val gatedSteps get() = """{"steps":[${humanStep("review-gate")},${agentStep("build", "[\"review-gate\"]")}]}"""

    /**
     * Deterministic capability: records the executed steps, completes agents, opens a checkpoint for humans.
     * [onExpanderRun] runs when the expanding step executes (the instant a continuation was just submitted).
     */
    private fun capability(
        workflowId: String,
        expanderAttemptId: String,
        called: MutableList<String>,
        expanderId: String = expander,
        onExpanderRun: () -> Unit = {},
    ): CapabilityExecutionService {
        val capability = mockk<CapabilityExecutionService>()
        every { capability.hasFinalizedAttempt(any(), any(), any(), any()) } returns false
        every { capability.canResumeWaitingAgentStep(any(), any(), any(), any()) } returns false
        every { capability.hasAcceptedFinalResult(any(), any(), any(), any()) } returns false
        every { capability.resolveAndRecord(any(), any(), any(), any(), any(), any(), any()) } answers {
            val step = invocation.args[3] as WorkflowStepDefinition
            called += step.id
            if (step.id == expanderId) onExpanderRun()
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
                    attemptId = if (step.id == expanderId) expanderAttemptId else "attempt-${step.id}",
                )
            }
        }
        return capability
    }

    private fun runner(
        capability: CapabilityExecutionService,
        repo: WorkflowRepository = repository,
        precheck: WorkspacePrecheckService? = null,
    ) = SessionRunService(
        repo, evidence, interactions, capability, hub,
        transactionManager = transactions,
        workspacePrecheckService = precheck,
        expansionService = expansion,
    )

    private fun workflowService(runner: SessionRunService) =
        WorkflowService(repository, evidence, interactions, hub, sessionRunService = runner, transactionManager = transactions)

    private fun answer(runner: SessionRunService, workflowId: String, stepId: String, action: String) {
        workflowService(runner).replyInteraction(
            scope, namespace, workflowId, "interaction-$stepId", 1, action, null, "alice", Path.of("."),
        )
    }

    private fun stepStatuses(workflowId: String) =
        repository.findStepStates(scope, namespace, workflowId).associate { it.stepId to it.status }

    private fun expansionEvidence(workflowId: String) =
        evidence.list(scope, namespace, workflowId).count { it.kind == WorkflowExpansionService.EVIDENCE_KIND }

    /** A repository whose finalization of the expanding step (COMPLETED transition) fails, once armed. */
    private class FailingFinalization(private val delegate: WorkflowRepository, private val stepId: String) :
        WorkflowRepository by delegate {
        @Volatile var armed = true

        override fun appendTransition(
            scope: TenantScope,
            namespaceId: String,
            workflowId: String,
            transitionId: String,
            request: WorkflowTransitionRequest,
            fromStepId: String?,
            toStepId: String,
            payload: Map<String, Any?>,
        ) {
            if (armed && request.stepId == stepId && request.requestedStatus == WorkflowStatuses.COMPLETED) {
                throw IllegalStateException("simulated failure while finalizing the expanding step")
            }
            delegate.appendTransition(scope, namespaceId, workflowId, transitionId, request, fromStepId, toStepId, payload)
        }
    }

    @Test
    fun `the added steps run in order right after the expanding step, on the same run`() {
        val workflowId = "expansion-seq-order"
        startRun(workflowId)
        val definitionsBefore = repository.listDefinitions(scope).map { it.workflowType to it.definitionHash }
        val attemptId = submitExpandingResult(workflowId, twoSteps)
        val called = mutableListOf<String>()
        val runner = runner(capability(workflowId, attemptId, called))

        val result = runner.runSession(scope, namespace, workflowId, Path.of("."))

        // Nothing is inserted by Factory: the steps the agent wrote run directly after the Architect step.
        assertThat(result.status).isEqualTo(WorkflowStatuses.COMPLETED)
        assertThat(called).containsExactly(expander, "build", "review")
        assertThat(stepStatuses(workflowId).values).containsOnly(WorkflowStatuses.COMPLETED)
        val run = repository.findInstance(scope, namespace, workflowId)!!
        assertThat((run.projection["steps"] as List<*>).filterIsInstance<Map<*, *>>().map { it["id"] })
            .containsExactly(expander, "build", "review")
        assertThat(run.projection["status"]).isEqualTo(WorkflowStatuses.COMPLETED)
        assertThat(expansionEvidence(workflowId)).isEqualTo(1)
        assertThat(repository.listDefinitions(scope).map { it.workflowType to it.definitionHash }).isEqualTo(definitionsBefore)

        // Replay: nothing runs again and nothing is added again.
        val replay = runner.runSession(scope, namespace, workflowId, Path.of("."))
        assertThat(replay.status).isEqualTo(WorkflowStatuses.COMPLETED)
        assertThat(called).containsExactly(expander, "build", "review")
        assertThat(expansionEvidence(workflowId)).isEqualTo(1)
    }

    @Test
    fun `a human step written by the Architect suspends the run, and a reject blocks its dependants`() {
        val workflowId = "expansion-seq-human-gate"
        startRun(workflowId)
        val attemptId = submitExpandingResult(workflowId, gatedSteps)
        val called = mutableListOf<String>()
        val runner = runner(capability(workflowId, attemptId, called))

        val first = runner.runSession(scope, namespace, workflowId, Path.of("."))

        // The run waits on the Architect's own human step; the step that depends on it has not run.
        assertThat(first.status).isEqualTo(WorkflowStatuses.WAITING_HUMAN)
        assertThat(called).containsExactly(expander, "review-gate")
        assertThat(stepStatuses(workflowId))
            .containsEntry(expander, WorkflowStatuses.COMPLETED)
            .containsEntry("review-gate", WorkflowStatuses.WAITING_HUMAN)
            .containsEntry("build", WorkflowStatuses.PENDING)

        // The reply goes through WorkflowService, which must follow the effective definition as well.
        answer(runner, workflowId, "review-gate", "reject")

        assertThat(called).containsExactly(expander, "review-gate")
        assertThat(stepStatuses(workflowId))
            .containsEntry("review-gate", WorkflowStatuses.FAILED)
            .containsEntry("build", WorkflowStatuses.BLOCKED)
        assertThat(repository.findInstance(scope, namespace, workflowId)!!.projection["status"]).isEqualTo(WorkflowStatuses.FAILED)
    }

    @Test
    fun `an approved human step written by the Architect lets its dependants run`() {
        val workflowId = "expansion-seq-human-approve"
        startRun(workflowId)
        val attemptId = submitExpandingResult(workflowId, gatedSteps)
        val called = mutableListOf<String>()
        val runner = runner(capability(workflowId, attemptId, called))
        runner.runSession(scope, namespace, workflowId, Path.of("."))

        answer(runner, workflowId, "review-gate", "approve")

        assertThat(called).containsExactly(expander, "review-gate", "build")
        assertThat(stepStatuses(workflowId).values).containsOnly(WorkflowStatuses.COMPLETED)
        assertThat(repository.findInstance(scope, namespace, workflowId)!!.projection["status"]).isEqualTo(WorkflowStatuses.COMPLETED)
    }

    @Test
    fun `a failure while finalizing the expanding step adds nothing, fails the step and never blocks a later expansion`() {
        val workflowId = "expansion-seq-atomic"
        startRun(workflowId)
        val attemptId = submitExpandingResult(workflowId, twoSteps)
        val failing = FailingFinalization(repository, expander)
        val called = mutableListOf<String>()
        val runner = runner(capability(workflowId, attemptId, called), repo = failing)

        val result = runner.runSession(scope, namespace, workflowId, Path.of("."))

        // One transaction: the steps were NOT kept and no expansion is recorded.
        assertThat(result.status).isEqualTo(WorkflowStatuses.FAILED)
        assertThat(called).containsExactly(expander)
        assertThat(stepStatuses(workflowId)).containsEntry(expander, WorkflowStatuses.FAILED)
        assertThat(stepStatuses(workflowId).keys).doesNotContain("build", "review")
        val run = repository.findInstance(scope, namespace, workflowId)!!
        assertThat(run.instance["expansion"]).isNull()
        assertThat((run.projection["steps"] as List<*>).filterIsInstance<Map<*, *>>().map { it["id"] }).containsExactly(expander)
        assertThat(expansionEvidence(workflowId)).isEqualTo(0)

        // No EXPANSION_IMMUTABLE trap: the same accepted result can still be added once the failure is gone.
        failing.armed = false
        val expanderStep = repository.findDefinition(scope, "architect-custom-flow", "1.0.0")!!.toPolicyDefinition().steps.single()
        val added = expansion.graft(scope, namespace, workflowId, expanderStep, attemptId)
        assertThat(added.map { it.id }).containsExactly("build", "review")
    }

    @Test
    fun `the workspace precheck runs again on the full list before the first added step, and nothing added starts when it fails`() {
        val workflowId = "expansion-seq-precheck"
        startRun(workflowId)
        val attemptId = submitExpandingResult(workflowId, twoSteps)
        val precheck = WorkspacePrecheckService(agents, WorkspaceContextPort { true }, WorkspaceProperties())
        val called = mutableListOf<String>()
        // An agent of the namespace disappears between the submission and the addition of the steps.
        val runner = runner(
            capability(workflowId, attemptId, called) { agents.agents = listOf("Frontend") },
            precheck = precheck,
        )

        assertThatThrownBy { runner.runSession(scope, namespace, workflowId, Path.of(".")) }
            .isInstanceOf(WorkspacePrecheckException::class.java)

        // The steps were added with the finalization, but no added step has started.
        assertThat(called).containsExactly(expander)
        assertThat(stepStatuses(workflowId))
            .containsEntry("build", WorkflowStatuses.PENDING)
            .containsEntry("review", WorkflowStatuses.PENDING)
    }

    @Test
    fun `a starting workflow of another circle works the same, with its own agent and its own step id`() {
        val workflowId = "expansion-seq-other-circle"
        val circleAgent = "Planner"
        val circleStep = "plan"
        // The circle's agent and the agent of the added steps are enabled in the namespace; Archay is not.
        agents.agents = listOf(circleAgent, "Frontend")
        startRun(workflowId, circleDefinition("ba-custom-flow", circleStep, circleAgent))
        val definitionsBefore = repository.listDefinitions(scope).map { it.workflowType to it.definitionHash }
        val continuation = """{"steps":[${agentStep("build", name = "Frontend")},${agentStep("review", "[\"build\"]", "Frontend")}]}"""
        val attemptId = submitExpandingResult(workflowId, continuation, expanderId = circleStep, agentName = circleAgent)
        val called = mutableListOf<String>()
        val runner = runner(capability(workflowId, attemptId, called, expanderId = circleStep))

        val result = runner.runSession(scope, namespace, workflowId, Path.of("."))

        // Nothing depends on architect-custom-flow, on Archay or on the step id `architect-plan`.
        assertThat(result.status).isEqualTo(WorkflowStatuses.COMPLETED)
        assertThat(called).containsExactly(circleStep, "build", "review")
        assertThat(stepStatuses(workflowId).values).containsOnly(WorkflowStatuses.COMPLETED)
        val run = repository.findInstance(scope, namespace, workflowId)!!
        assertThat(run.instance["workflowType"]).isEqualTo("ba-custom-flow")
        assertThat((run.projection["steps"] as List<*>).filterIsInstance<Map<*, *>>().map { it["id"] })
            .containsExactly(circleStep, "build", "review")
        assertThat(expansionEvidence(workflowId)).isEqualTo(1)
        assertThat(repository.listDefinitions(scope).map { it.workflowType to it.definitionHash }).isEqualTo(definitionsBefore)
    }

    @Test
    fun `a run whose expanding step failed adds no steps`() {
        val workflowId = "expansion-seq-failed"
        startRun(workflowId)
        repository.upsertStepState(
            scope,
            io.whozoss.factory.workflow.domain.WorkflowStepStateRecord(namespace, workflowId, expander, 1, WorkflowStatuses.FAILED, emptyMap()),
        )
        val called = mutableListOf<String>()

        val result = runner(capability(workflowId, "none", called)).runSession(scope, namespace, workflowId, Path.of("."))

        assertThat(result.status).isEqualTo(WorkflowStatuses.FAILED)
        assertThat(called).isEmpty()
        assertThat(repository.findInstance(scope, namespace, workflowId)!!.instance["expansion"]).isNull()
    }
}
