package io.whozoss.factory.workflow.service

import io.whozoss.factory.capability.CapabilityExecution
import io.whozoss.factory.capability.CapabilityExecutionService
import io.whozoss.factory.capability.CapabilityOutcome
import io.whozoss.factory.persistence.TenantScope
import io.whozoss.factory.workflow.domain.CanonicalHash
import io.whozoss.factory.workflow.domain.HumanInteractionRecord
import io.whozoss.factory.workflow.domain.SessionSequencer
import io.whozoss.factory.workflow.domain.WorkflowErrorCodes
import io.whozoss.factory.workflow.domain.WorkflowEvidenceItem
import io.whozoss.factory.workflow.domain.WorkflowInstanceRecord
import io.whozoss.factory.workflow.domain.WorkflowProjectionRecord
import io.whozoss.factory.workflow.domain.WorkflowStatuses
import io.whozoss.factory.workflow.domain.WorkflowStepDefinition
import io.whozoss.factory.workflow.domain.WorkflowStepStateRecord
import io.whozoss.factory.workflow.domain.WorkflowTransitionRequest
import io.whozoss.factory.workflow.domain.nowIso
import io.whozoss.factory.workflow.domain.workflowException
import io.whozoss.factory.workflow.persistence.HumanInteractionRepository
import io.whozoss.factory.workflow.persistence.WorkflowEvidenceRepository
import io.whozoss.factory.workflow.persistence.WorkflowRepository
import io.whozoss.factory.workflow.sse.WorkflowSseHub
import java.nio.file.Path
import java.util.UUID
import org.springframework.stereotype.Service
import org.springframework.transaction.annotation.Transactional

/** The projected state of one session step. */
data class SessionStepState(val stepId: String, val status: String)

/** The projected state of a session after a run/resume. */
data class SessionRunResult(
    val namespaceId: String,
    val workflowId: String,
    val status: String,
    val steps: List<SessionStepState>,
)

/**
 * Automatic DAG execution of a declarative session (W8.3).
 *
 * The sequencer runs the whole DAG by itself (never step by step). At every
 * iteration it evaluates the pure [SessionSequencer] rules, executes ONE ready
 * step through [CapabilityExecutionService] and applies the failure rule:
 *  - a succeeded step releases its dependents (`ready`);
 *  - a failed step marks ALL its transitive dependents `blocked`;
 *  - independent branches keep running;
 *  - a `human` step suspends the session in `waiting_human` until the interaction
 *    is answered; re-running the sequencer resumes from the current state;
 *  - when no step is `ready` any more the session is `completed` (all passed) or
 *    `failed` (any failed/blocked). There is NO automatic retry.
 *
 * Steps are executed sequentially (one ready step per iteration) rather than in
 * parallel: this is the documented, deterministic choice for W8.3 — it keeps the
 * projection/evidence ordering stable and the failure propagation easy to reason
 * about. The DAG structure (independent branches) is still honoured: a failure on
 * one branch never stops another.
 *
 * Every step records its state (`workflow_step_states`), a transition
 * (`workflow_transitions`) and a `session-step` evidence item, then the instance
 * projection is refreshed for the cockpit timeline.
 *
 * Note: the run shares a single transaction with the HTTP agent-turn call; a long
 * agent turn therefore holds the transaction. This is acceptable for W8.3 and is
 * revisited with the projection/cockpit wave (W8.4).
 */
@Service
class SessionRunService(
    private val repository: WorkflowRepository,
    private val evidenceRepository: WorkflowEvidenceRepository,
    private val interactionRepository: HumanInteractionRepository,
    private val capabilityExecutionService: CapabilityExecutionService,
    private val sseHub: WorkflowSseHub,
) {

    /** Runs (or resumes) the session DAG to a terminal state or to a human suspension. */
    @Transactional
    fun runSession(
        scope: TenantScope,
        namespaceId: String,
        workflowId: String,
        repoRoot: Path,
    ): SessionRunResult {
        val instance = activeInstance(scope, namespaceId, workflowId)
        val steps = resolveDefinition(scope, instance).steps
        if (steps.isEmpty()) {
            throw workflowException(WorkflowErrorCodes.INVALID_START_REQUEST, "The session has no steps.")
        }
        val statuses = loadStatuses(scope, namespaceId, workflowId, steps)
        var suspended = false
        val guard = steps.size * 4 + 8
        var iterations = 0
        run {
            while (iterations++ < guard) {
                applyEvaluation(scope, namespaceId, workflowId, steps, statuses)
                if (statuses.values.any { it == WorkflowStatuses.WAITING_HUMAN }) {
                    if (!resolveWaitingHuman(scope, namespaceId, workflowId, steps, statuses)) {
                        suspended = true
                        return@run
                    }
                    continue
                }
                val readyId = SessionSequencer.readySteps(steps, statuses).firstOrNull() ?: return@run
                val step = steps.first { it.id == readyId }
                executeStep(scope, namespaceId, workflowId, steps, statuses, step, repoRoot)?.let { terminal ->
                    if (terminal == WorkflowStatuses.WAITING_HUMAN) {
                        suspended = true
                        return@run
                    }
                }
            }
        }
        val sessionStatus = if (suspended) WorkflowStatuses.WAITING_HUMAN else SessionSequencer.terminalStatus(steps, statuses)
        persistProjection(scope, namespaceId, workflowId, steps, statuses, sessionStatus)
        sseHub.publish(namespaceId, mapOf("workflowId" to workflowId, "namespaceId" to namespaceId))
        return SessionRunResult(namespaceId, workflowId, sessionStatus, statusesOf(steps, statuses))
    }

    /** Read-only projection of the current session state. */
    @Transactional(readOnly = true)
    fun sessionState(scope: TenantScope, namespaceId: String, workflowId: String): SessionRunResult? {
        val instance = repository.findInstance(scope, namespaceId, workflowId) ?: return null
        val steps = resolveDefinition(scope, instance).steps
        if (steps.isEmpty()) {
            return SessionRunResult(namespaceId, workflowId, sessionStatus(instance), emptyList())
        }
        val stored = repository.findStepStates(scope, namespaceId, workflowId).associateBy { it.stepId }
        val statuses = LinkedHashMap<String, String>()
        for (step in steps) {
            statuses[step.id] = stored[step.id]?.status ?: WorkflowStatuses.PENDING
        }
        return SessionRunResult(namespaceId, workflowId, sessionStatus(instance), statusesOf(steps, statuses))
    }

    private fun sessionStatus(instance: WorkflowInstanceRecord): String =
        (instance.projection["status"] as? String) ?: (instance.instance["status"] as? String) ?: "unknown"

    private fun statusesOf(
        steps: List<WorkflowStepDefinition>,
        statuses: Map<String, String>,
    ): List<SessionStepState> = steps.map { SessionStepState(it.id, statuses[it.id] ?: WorkflowStatuses.PENDING) }

    private fun loadStatuses(
        scope: TenantScope,
        namespaceId: String,
        workflowId: String,
        steps: List<WorkflowStepDefinition>,
    ): LinkedHashMap<String, String> {
        val statuses = LinkedHashMap<String, String>()
        val existing = repository.findStepStates(scope, namespaceId, workflowId)
        if (existing.isEmpty()) {
            val initial = SessionSequencer.initialStatuses(steps)
            for (step in steps) {
                val status = initial[step.id] ?: WorkflowStatuses.PENDING
                setStatus(scope, namespaceId, workflowId, step.id, status, statuses)
            }
            return statuses
        }
        val byId = existing.associateBy { it.stepId }
        for (step in steps) {
            val stored = byId[step.id]?.status ?: WorkflowStatuses.PENDING
            // A step left `running` by an interrupted run is re-scheduled: the
            // in-process sequencer is the only owner of the `running` state.
            statuses[step.id] = if (stored == WorkflowStatuses.RUNNING) WorkflowStatuses.READY else stored
        }
        return statuses
    }

    private fun applyEvaluation(
        scope: TenantScope,
        namespaceId: String,
        workflowId: String,
        steps: List<WorkflowStepDefinition>,
        statuses: LinkedHashMap<String, String>,
    ) {
        val evaluation = SessionSequencer.evaluate(steps, statuses)
        for (stepId in evaluation.blocked) setStatus(scope, namespaceId, workflowId, stepId, WorkflowStatuses.BLOCKED, statuses)
        for (stepId in evaluation.ready) setStatus(scope, namespaceId, workflowId, stepId, WorkflowStatuses.READY, statuses)
    }

    /** Executes one ready step; returns its terminal status, or null on an unexpected failure path. */
    private fun executeStep(
        scope: TenantScope,
        namespaceId: String,
        workflowId: String,
        steps: List<WorkflowStepDefinition>,
        statuses: LinkedHashMap<String, String>,
        step: WorkflowStepDefinition,
        repoRoot: Path,
    ): String? {
        setStatus(scope, namespaceId, workflowId, step.id, WorkflowStatuses.RUNNING, statuses)
        val execution = try {
            capabilityExecutionService.resolveAndRecord(scope, namespaceId, workflowId, step, repoRoot)
        } catch (error: Exception) {
            recordFailureEvidence(scope, namespaceId, workflowId, step, error)
            transition(scope, namespaceId, workflowId, step, WorkflowStatuses.FAILED)
            setStatus(scope, namespaceId, workflowId, step.id, WorkflowStatuses.FAILED, statuses)
            return WorkflowStatuses.FAILED
        }
        val terminal = classify(execution.outcome)
        transition(scope, namespaceId, workflowId, step, terminal)
        setStatus(scope, namespaceId, workflowId, step.id, terminal, statuses)
        recordStepEvidence(scope, namespaceId, workflowId, step, terminal, execution)
        return terminal
    }

    private fun classify(outcome: CapabilityOutcome): String = when (outcome) {
        is CapabilityOutcome.CodeExecuted -> if (outcome.verdict) WorkflowStatuses.COMPLETED else WorkflowStatuses.FAILED
        is CapabilityOutcome.CodeRefused -> WorkflowStatuses.FAILED
        is CapabilityOutcome.AgentCompleted -> WorkflowStatuses.COMPLETED
        is CapabilityOutcome.AgentFailed -> WorkflowStatuses.FAILED
        is CapabilityOutcome.AgentDeferred -> WorkflowStatuses.FAILED
        is CapabilityOutcome.HumanCheckpointRequired -> WorkflowStatuses.WAITING_HUMAN
    }

    /**
     * Resolves the steps suspended on a human interaction from the durable
     * `human_interactions` journal: a closed interaction with `approve` completes
     * the step, any other decision fails it. Returns false when at least one step
     * is still awaiting an answer (the session stays suspended).
     */
    private fun resolveWaitingHuman(
        scope: TenantScope,
        namespaceId: String,
        workflowId: String,
        steps: List<WorkflowStepDefinition>,
        statuses: LinkedHashMap<String, String>,
    ): Boolean {
        val interactions = interactionRepository.list(scope, namespaceId, workflowId, openOnly = false)
        var allResolved = true
        for (step in steps) {
            if (statuses[step.id] != WorkflowStatuses.WAITING_HUMAN) continue
            val interaction = interactions.filter { it.stepId == step.id }.maxByOrNull { it.revision }
            val terminal = humanDecision(interaction)
            if (terminal == null || interaction == null) {
                allResolved = false
                continue
            }
            transition(scope, namespaceId, workflowId, step, terminal)
            setStatus(scope, namespaceId, workflowId, step.id, terminal, statuses)
            recordHumanResolutionEvidence(scope, namespaceId, workflowId, step, terminal, interaction)
        }
        return allResolved
    }

    @Suppress("UNCHECKED_CAST")
    private fun humanDecision(interaction: HumanInteractionRecord?): String? {
        if (interaction == null || interaction.status != "closed") return null
        val response = interaction.payload["response"] as? Map<*, *>
        val actionId = response?.get("actionId") as? String
        return if (actionId == "approve") WorkflowStatuses.COMPLETED else WorkflowStatuses.FAILED
    }

    private fun setStatus(
        scope: TenantScope,
        namespaceId: String,
        workflowId: String,
        stepId: String,
        status: String,
        statuses: LinkedHashMap<String, String>,
    ) {
        if (statuses[stepId] == status) return
        statuses[stepId] = status
        repository.upsertStepState(
            scope,
            WorkflowStepStateRecord(
                namespaceId = namespaceId,
                workflowId = workflowId,
                stepId = stepId,
                revision = 1,
                status = status,
            ),
        )
    }

    private fun transition(
        scope: TenantScope,
        namespaceId: String,
        workflowId: String,
        step: WorkflowStepDefinition,
        status: String,
    ) {
        repository.appendTransition(
            scope,
            namespaceId,
            workflowId,
            transitionId = UUID.randomUUID().toString(),
            request = WorkflowTransitionRequest(
                requestId = UUID.randomUUID().toString(),
                workflowId = workflowId,
                stepId = step.id,
                expectedRevision = 1,
                requestedStatus = status,
                evidenceIds = emptyList(),
            ),
            fromStepId = step.id,
            toStepId = step.id,
            payload = mapOf("kind" to "session-step", "status" to status),
        )
    }

    private fun recordStepEvidence(
        scope: TenantScope,
        namespaceId: String,
        workflowId: String,
        step: WorkflowStepDefinition,
        status: String,
        execution: CapabilityExecution,
    ) {
        evidenceRepository.append(
            scope,
            namespaceId,
            workflowId,
            WorkflowEvidenceItem(
                evidenceId = UUID.randomUUID().toString(),
                namespaceId = namespaceId,
                workflowId = workflowId,
                stepId = step.id,
                kind = "session-step",
                outcome = status,
                source = mapOf(
                    "kind" to "factory-session-sequencer",
                    "responsibility" to step.responsibility.kind.wire,
                    "name" to step.responsibility.name,
                ),
                facts = mapOf(
                    "stepId" to step.id,
                    "status" to status,
                    "attemptId" to execution.attemptId,
                    "evidenceId" to execution.evidenceId,
                ),
                idempotencyKey = null,
                createdAt = null,
            ),
        )
    }

    private fun recordFailureEvidence(
        scope: TenantScope,
        namespaceId: String,
        workflowId: String,
        step: WorkflowStepDefinition,
        error: Exception,
    ) {
        evidenceRepository.append(
            scope,
            namespaceId,
            workflowId,
            WorkflowEvidenceItem(
                evidenceId = UUID.randomUUID().toString(),
                namespaceId = namespaceId,
                workflowId = workflowId,
                stepId = step.id,
                kind = "session-step",
                outcome = WorkflowStatuses.FAILED,
                source = mapOf("kind" to "factory-session-sequencer"),
                facts = mapOf(
                    "stepId" to step.id,
                    "status" to WorkflowStatuses.FAILED,
                    "code" to "STEP_EXECUTION_ERROR",
                    "message" to (error.message ?: error.toString()),
                ),
                idempotencyKey = null,
                createdAt = null,
            ),
        )
    }

    private fun recordHumanResolutionEvidence(
        scope: TenantScope,
        namespaceId: String,
        workflowId: String,
        step: WorkflowStepDefinition,
        status: String,
        interaction: HumanInteractionRecord,
    ) {
        evidenceRepository.append(
            scope,
            namespaceId,
            workflowId,
            WorkflowEvidenceItem(
                evidenceId = UUID.randomUUID().toString(),
                namespaceId = namespaceId,
                workflowId = workflowId,
                stepId = step.id,
                kind = "human-decision",
                outcome = if (status == WorkflowStatuses.COMPLETED) "pass" else "fail",
                source = mapOf("kind" to "factory-human", "interactionId" to interaction.interactionId),
                facts = mapOf("stepId" to step.id, "status" to status, "interactionId" to interaction.interactionId),
                idempotencyKey = "human:$workflowId:${interaction.interactionId}",
                createdAt = null,
            ),
        )
    }

    private fun persistProjection(
        scope: TenantScope,
        namespaceId: String,
        workflowId: String,
        steps: List<WorkflowStepDefinition>,
        statuses: Map<String, String>,
        sessionStatus: String,
    ) {
        val instance = repository.findInstance(scope, namespaceId, workflowId) ?: return
        val nextInstance = instance.instance.toMutableMap()
        nextInstance["status"] = sessionStatus
        nextInstance["steps"] = steps.map { mapOf("id" to it.id, "status" to (statuses[it.id] ?: WorkflowStatuses.PENDING)) }
        nextInstance["updatedAt"] = nowIso()
        val projection = instance.projection.toMutableMap()
        projection["status"] = sessionStatus
        projection["steps"] = steps.map { step ->
            linkedMapOf<String, Any?>(
                "id" to step.id,
                "name" to step.name,
                "status" to (statuses[step.id] ?: WorkflowStatuses.PENDING),
                "dependsOn" to step.dependsOn,
                "responsibility" to step.responsibility.toJson(),
            )
        }
        val next = instance.copy(revision = instance.revision + 1, instance = nextInstance, projection = projection)
        repository.updateInstance(scope, namespaceId, workflowId, instance.revision, next)

        runCatching {
            val existingProjection = repository.findProjection(scope, namespaceId, workflowId)
            repository.publishProjection(
                scope,
                WorkflowProjectionRecord(
                    namespaceId = namespaceId,
                    workflowId = workflowId,
                    schemaVersion = (projection["schemaVersion"] as? String) ?: "2",
                    revision = 0,
                    projectionHash = CanonicalHash.workflowProjectionHash(projection),
                    status = sessionStatus,
                    projection = projection,
                    instance = nextInstance,
                    governanceMode = existingProjection?.governanceMode ?: "governed",
                    definitionVersion = existingProjection?.definitionVersion
                        ?: instance.instance["definitionVersion"] as? String,
                    definitionHash = existingProjection?.definitionHash
                        ?: instance.instance["definitionHash"] as? String,
                    relations = existingProjection?.relations,
                    controllerExecution = existingProjection?.controllerExecution,
                    lifecycleState = "active",
                ),
                expectedRevision = null,
            )
        }
    }

    private fun activeInstance(scope: TenantScope, namespaceId: String, workflowId: String): WorkflowInstanceRecord {
        val instance = repository.findInstance(scope, namespaceId, workflowId)
            ?: throw workflowException(WorkflowErrorCodes.WORKFLOW_NOT_FOUND)
        if (instance.status != "active") throw workflowException(WorkflowErrorCodes.WORKFLOW_REMOVED)
        return instance
    }

    private fun resolveDefinition(scope: TenantScope, instance: WorkflowInstanceRecord) =
        run {
            val workflowType = instance.instance["workflowType"] as? String
            val version = instance.instance["definitionVersion"] as? String
            val record = if (workflowType != null && version != null) {
                repository.findDefinition(scope, workflowType, version)
            } else {
                null
            } ?: throw workflowException(WorkflowErrorCodes.WORKFLOW_DEFINITION_NOT_FOUND)
            if (record.definitionHash != instance.instance["definitionHash"]) {
                throw workflowException(WorkflowErrorCodes.WORKFLOW_DEFINITION_MISMATCH)
            }
            record.toPolicyDefinition()
        }
}
