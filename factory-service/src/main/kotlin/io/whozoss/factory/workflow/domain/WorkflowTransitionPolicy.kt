package io.whozoss.factory.workflow.domain

import java.time.Instant
import java.util.UUID

/**
 * Pure workflow transition policy.
 *
 * Port of `factory/src/domain/workflow/workflow-transition-policy.ts`:
 * request validation, the status state machine, transition evaluation and the
 * pure `apply*` functions that materialize the next snapshot. No I/O.
 */

/** A snapshot as seen by the policy: the governed instance + its projection. */
data class WorkflowSnapshot(
    val revision: Int,
    val governanceMode: String?,
    val definitionVersion: String?,
    val definitionHash: String?,
    val controllerExecution: Map<String, Any?>?,
    val instance: Map<String, Any?>,
    val projection: Map<String, Any?>,
)

/** The resolved definition as seen by the policy. */
data class WorkflowPolicyDefinition(
    val workflowType: String,
    val version: String,
    val definitionHash: String,
    val steps: List<WorkflowStepDefinition>,
)

/** A piece of evidence selected by a transition request. */
data class WorkflowPolicyEvidence(
    val evidenceId: String,
    val namespaceId: String?,
    val workflowId: String?,
    val stepId: String?,
    val kind: String,
    val outcome: String?,
    val source: Map<String, Any?>?,
    val facts: Map<String, Any?>,
)

/** The result of a policy evaluation. */
sealed interface TransitionDecision {
    val allowed: Boolean

    data object Allowed : TransitionDecision {
        override val allowed: Boolean = true
    }

    data class Denied(
        val code: String,
        val reason: String,
        val missingEvidence: List<String>? = null,
    ) : TransitionDecision {
        override val allowed: Boolean = false
    }
}

/** Validation result of an incoming transition request. */
sealed interface TransitionRequestValidation {
    data class Valid(val request: WorkflowTransitionRequest) : TransitionRequestValidation
    data class Invalid(val code: String) : TransitionRequestValidation
}

private val SAFE_ID = Regex("^[A-Za-z0-9][A-Za-z0-9._-]{0,127}$")
private val TRANSITION_FIELDS = setOf(
    "requestId",
    "workflowId",
    "stepId",
    "expectedRevision",
    "requestedStatus",
    "evidenceIds",
    "idempotencyKey",
)

object WorkflowTransitionPolicy {

    private fun deny(code: String, reason: String, missingEvidence: List<String>? = null) =
        TransitionDecision.Denied(code, reason, missingEvidence)

    private fun invalid() = TransitionRequestValidation.Invalid("INVALID_TRANSITION_REQUEST")

    /** Port of `validateWorkflowTransitionRequest`. `requestId` is server-generated. */
    fun validateRequest(input: Any?, expectedWorkflowId: String): TransitionRequestValidation {
        if (input !is Map<*, *>) return invalid()
        val record = input.entries.associate { it.key.toString() to it.value }
        if (record.keys.any { it !in TRANSITION_FIELDS }) return invalid()
        if (record.containsKey("requestId")) return TransitionRequestValidation.Invalid("UNTRUSTED_REQUEST_ID")
        val workflowId = record["workflowId"] as? String
        val stepId = record["stepId"] as? String
        val expectedRevision = (record["expectedRevision"] as? Number)?.toInt()
        val requestedStatus = record["requestedStatus"] as? String
        val evidenceIds = record["evidenceIds"] as? List<*>
        val idempotencyKey = record["idempotencyKey"] as? String
        // `workflowId != expectedWorkflowId` already rejects a null workflowId
        // (expectedWorkflowId is non-null), so the extra null guard is redundant.
        if (workflowId != expectedWorkflowId || !SAFE_ID.matches(workflowId) ||
            stepId == null || !SAFE_ID.matches(stepId)
        ) {
            return invalid()
        }
        if (expectedRevision == null || expectedRevision < 1 || !WorkflowStatuses.isKnown(requestedStatus)) return invalid()
        if (evidenceIds == null || evidenceIds.size > 100 || evidenceIds.distinct().size != evidenceIds.size ||
            evidenceIds.any { it !is String || !SAFE_ID.matches(it) }
        ) {
            return invalid()
        }
        if (record.containsKey("idempotencyKey") &&
            (idempotencyKey == null || idempotencyKey.isEmpty() || idempotencyKey.length > 128 ||
                idempotencyKey.contains('\r') || idempotencyKey.contains('\n'))
        ) {
            return invalid()
        }
        return TransitionRequestValidation.Valid(
            WorkflowTransitionRequest(
                requestId = UUID.randomUUID().toString(),
                workflowId = workflowId,
                stepId = stepId,
                expectedRevision = expectedRevision,
                requestedStatus = requestedStatus!!,
                evidenceIds = evidenceIds.map { it as String },
                idempotencyKey = idempotencyKey?.takeIf { it.isNotEmpty() },
            ),
        )
    }

    private fun stepsOf(instance: Map<String, Any?>): List<Map<String, Any?>> =
        (instance["steps"] as? List<*>)?.mapNotNull { entry ->
            (entry as? Map<*, *>)?.entries?.associate { it.key.toString() to it.value }
        }.orEmpty()

    private fun projectionSteps(projection: Map<String, Any?>): List<Map<String, Any?>> =
        (projection["steps"] as? List<*>)?.mapNotNull { entry ->
            (entry as? Map<*, *>)?.entries?.associate { it.key.toString() to it.value }
        }.orEmpty()

    private fun stepStatus(steps: List<Map<String, Any?>>, id: String): String? =
        steps.firstOrNull { it["id"] == id }?.get("status") as? String

    /** Definition identity must match both the instance and the snapshot envelope. */
    private fun definitionMatches(
        snapshot: WorkflowSnapshot,
        definition: WorkflowPolicyDefinition,
    ): Boolean {
        val instance = snapshot.instance
        return instance["workflowType"] == definition.workflowType &&
            instance["definitionVersion"] == definition.version &&
            instance["definitionHash"] == definition.definitionHash &&
            snapshot.definitionVersion == definition.version &&
            snapshot.definitionHash == definition.definitionHash
    }

    /** Port of `evaluateWorkflowTransition`. */
    fun evaluateTransition(
        request: WorkflowTransitionRequest,
        snapshot: WorkflowSnapshot?,
        definition: WorkflowPolicyDefinition?,
        evidence: List<WorkflowPolicyEvidence>,
        execution: WorkflowExecution,
    ): TransitionDecision {
        if (snapshot == null) return deny("WORKFLOW_NOT_FOUND", "workflow_not_found")
        if (snapshot.governanceMode != WORKFLOW_GOVERNANCE_MODE ||
            snapshot.instance["governanceMode"] != WORKFLOW_GOVERNANCE_MODE
        ) {
            return deny("WORKFLOW_NOT_GOVERNED", "workflow_not_governed")
        }
        if (definition == null) return deny("WORKFLOW_DEFINITION_NOT_FOUND", "definition_not_found")
        if (!definitionMatches(snapshot, definition)) return deny("WORKFLOW_DEFINITION_MISMATCH", "definition_identity_mismatch")
        val instanceSteps = stepsOf(snapshot.instance)
        val declared = definition.steps.firstOrNull { it.id == request.stepId }
        val current = instanceSteps.firstOrNull { it["id"] == request.stepId }
        if (declared == null || current == null) return deny("STEP_NOT_FOUND", "step_not_found")
        if (request.expectedRevision != snapshot.revision ||
            (snapshot.instance["revision"] as? Number)?.toInt() != snapshot.revision
        ) {
            return deny("REVISION_CONFLICT", "revision_mismatch")
        }
        val currentStatus = current["status"] as? String
        if (currentStatus == null || request.requestedStatus !in WorkflowStatuses.TRANSITIONS[currentStatus].orEmpty()) {
            return deny("ILLEGAL_TRANSITION", "transition_not_allowed")
        }
        if (request.requestedStatus in setOf(WorkflowStatuses.READY, WorkflowStatuses.RUNNING, WorkflowStatuses.COMPLETED)) {
            val missing = declared.dependsOn.filter { stepStatus(instanceSteps, it) != WorkflowStatuses.COMPLETED }
            if (missing.isNotEmpty()) return deny("DEPENDENCIES_NOT_SATISFIED", "dependencies_not_completed", missing)
        }
        val factoryOracle = declared.responsibility.kind == ResponsibilityKind.CODE &&
            execution.kind == "factory-oracle" && execution.runtimeId == "factory-dashboard"
        val factoryHuman = declared.responsibility.kind == ResponsibilityKind.HUMAN &&
            execution.kind == "factory-human" && execution.runtimeId == "factory-dashboard" &&
            !execution.actorId.isNullOrEmpty()
        val factoryRetry = declared.responsibility.kind == ResponsibilityKind.AGENT &&
            currentStatus == WorkflowStatuses.BLOCKED && request.requestedStatus == WorkflowStatuses.READY &&
            execution.kind == "factory-control-plane" && execution.runtimeId == "factory-dashboard" &&
            execution.agentId == "factory-runner" && !execution.actorId.isNullOrEmpty()
        if (declared.responsibility.kind != ResponsibilityKind.AGENT && !factoryOracle && !factoryHuman) {
            return deny("ACTOR_NOT_AUTHORIZED", "runtime_cannot_transition_step_responsibility")
        }
        if (declared.responsibility.kind == ResponsibilityKind.AGENT && !factoryRetry &&
            !declared.responsibility.name.isNullOrEmpty() && declared.responsibility.name != execution.agentId
        ) {
            return deny("ACTOR_NOT_AUTHORIZED", "agent_responsibility_mismatch")
        }
        if (factoryHuman && currentStatus != WorkflowStatuses.WAITING_HUMAN) {
            return deny("INTERACTION_STALE", "human_step_is_not_waiting")
        }
        val selected = ArrayList<WorkflowPolicyEvidence>()
        for (id in request.evidenceIds) {
            val item = evidence.firstOrNull { it.evidenceId == id }
                ?: return deny("EVIDENCE_NOT_FOUND", "evidence_not_found", listOf(id))
            if (item.namespaceId != execution.namespaceId || item.workflowId != request.workflowId ||
                item.stepId != request.stepId
            ) {
                return deny("EVIDENCE_SCOPE_MISMATCH", "evidence_scope_mismatch")
            }
            selected.add(item)
        }
        if (request.requestedStatus == WorkflowStatuses.BLOCKED && declared.responsibility.kind == ResponsibilityKind.AGENT) {
            val negative = selected.any {
                it.kind == "agent-result" && (it.outcome == "fail" || it.outcome == "indeterminate") &&
                    sourceMatches(it.source, execution)
            }
            if (!negative) return deny("NEGATIVE_EVIDENCE_REQUIRED", "matching_agent_result_negative_required")
        }
        if (request.requestedStatus == WorkflowStatuses.READY && currentStatus == WorkflowStatuses.BLOCKED) {
            val controller = snapshot.controllerExecution ?: snapshot.controllerExecution
            if (execution.kind != "factory-control-plane" || execution.runtimeId != "factory-dashboard" ||
                execution.agentId != "factory-runner" || execution.actorId.isNullOrEmpty() ||
                controller == null || controller["caseId"] != execution.caseId
            ) {
                return deny("ACTOR_NOT_AUTHORIZED", "manual_retry_requires_factory_controller_and_human_actor")
            }
            val retry = selected.any {
                it.kind == "human-decision" && it.outcome == "pass" && it.source?.get("kind") == "factory-human" &&
                    !(it.source.get("actorId") as? String).isNullOrEmpty()
            }
            if (!retry) return deny("RETRY_EVIDENCE_REQUIRED", "trusted_manual_retry_evidence_required")
        }
        if (request.requestedStatus == WorkflowStatuses.COMPLETED) {
            when {
                factoryHuman -> {
                    val decision = selected.any {
                        it.kind == "human-decision" && it.outcome == "pass" &&
                            it.source?.get("kind") == "factory-human" && it.source.get("actorId") == execution.actorId
                    }
                    if (!decision) return deny("PASS_EVIDENCE_REQUIRED", "matching_human_decision_required")
                }
                factoryOracle -> {
                    val pass = selected.any {
                        it.kind == "oracle-result" && it.outcome == "pass" &&
                            it.source?.get("kind") == "factory-oracle" && it.facts["oracleId"] == declared.responsibility.name
                    }
                    if (!pass) return deny("PASS_EVIDENCE_REQUIRED", "matching_oracle_result_pass_required")
                }
                else -> {
                    if (selected.any { it.kind == "agent-result" && (it.outcome == "fail" || it.outcome == "indeterminate") }) {
                        return deny("EVIDENCE_NEGATIVE", "agent_result_not_pass")
                    }
                    val pass = selected.any { it.kind == "agent-result" && it.outcome == "pass" && sourceMatches(it.source, execution) }
                    if (!pass) return deny("PASS_EVIDENCE_REQUIRED", "matching_agent_result_pass_required")
                }
            }
        }
        return TransitionDecision.Allowed
    }

    private fun sourceMatches(source: Map<String, Any?>?, execution: WorkflowExecution): Boolean =
        source != null && source["kind"] == execution.kind && source["runtimeId"] == execution.runtimeId &&
            source["agentId"] == execution.agentId && source["caseId"] == execution.caseId &&
            source["threadId"] == execution.threadId

    /** Port of `evaluateHumanCheckpointOpen`: authorizes ready -> waiting_human. */
    fun evaluateHumanCheckpointOpen(
        request: WorkflowTransitionRequest,
        snapshot: WorkflowSnapshot?,
        definition: WorkflowPolicyDefinition?,
        execution: WorkflowExecution,
    ): TransitionDecision {
        if (snapshot == null) return deny("WORKFLOW_NOT_FOUND", "workflow_not_found")
        if (snapshot.governanceMode != WORKFLOW_GOVERNANCE_MODE ||
            snapshot.instance["governanceMode"] != WORKFLOW_GOVERNANCE_MODE
        ) {
            return deny("WORKFLOW_NOT_GOVERNED", "workflow_not_governed")
        }
        if (definition == null) return deny("WORKFLOW_DEFINITION_NOT_FOUND", "definition_not_found")
        if (!definitionMatches(snapshot, definition)) return deny("WORKFLOW_DEFINITION_MISMATCH", "definition_identity_mismatch")
        val instanceSteps = stepsOf(snapshot.instance)
        val declared = definition.steps.firstOrNull { it.id == request.stepId }
        val current = instanceSteps.firstOrNull { it["id"] == request.stepId }
        if (declared == null || current == null) return deny("STEP_NOT_FOUND", "step_not_found")
        if (request.expectedRevision != snapshot.revision ||
            (snapshot.instance["revision"] as? Number)?.toInt() != snapshot.revision
        ) {
            return deny("REVISION_CONFLICT", "revision_mismatch")
        }
        if (declared.responsibility.kind != ResponsibilityKind.HUMAN) {
            return deny("ACTOR_NOT_AUTHORIZED", "step_is_not_human_owned")
        }
        if (current["status"] != WorkflowStatuses.READY) return deny("ILLEGAL_TRANSITION", "human_step_is_not_ready")
        val missing = declared.dependsOn.filter { stepStatus(instanceSteps, it) != WorkflowStatuses.COMPLETED }
        if (missing.isNotEmpty()) return deny("DEPENDENCIES_NOT_SATISFIED", "dependencies_not_completed", missing)
        if (request.requestedStatus != WorkflowStatuses.WAITING_HUMAN || request.evidenceIds.isNotEmpty()) {
            return deny("ACTOR_NOT_AUTHORIZED", "human_gate_opener_can_only_open_checkpoint")
        }
        val factoryHumanGate = execution.kind == "factory-human-gate" &&
            execution.runtimeId == "factory-dashboard" && execution.agentId == "factory-runner" &&
            execution.actorId == null
        val controller = snapshot.instance["controllerExecution"] as? Map<*, *> ?: snapshot.controllerExecution
        val originalController = controller != null &&
            controller["kind"] == execution.kind && controller["runtimeId"] == execution.runtimeId &&
            controller["agentId"] == execution.agentId && controller["caseId"] == execution.caseId &&
            controller["threadId"] == execution.threadId
        if (!factoryHumanGate && !originalController) return deny("ACTOR_NOT_AUTHORIZED", "execution_cannot_open_human_gate")
        return TransitionDecision.Allowed
    }

    /** Port of `evaluateHumanResolutionTransition`: waiting_human -> completed|failed. */
    fun evaluateHumanResolutionTransition(
        request: WorkflowTransitionRequest,
        snapshot: WorkflowSnapshot?,
        definition: WorkflowPolicyDefinition?,
        evidence: List<WorkflowPolicyEvidence>,
        execution: WorkflowExecution,
    ): TransitionDecision {
        if (execution.kind == "factory-human-gate" || execution.kind != "factory-human" ||
            execution.runtimeId != "factory-dashboard" || execution.actorId.isNullOrEmpty()
        ) {
            return deny("ACTOR_NOT_AUTHORIZED", "human_resolution_requires_authenticated_human")
        }
        val present = snapshot ?: return deny("WORKFLOW_NOT_FOUND", "workflow_not_found")
        val current = stepsOf(present.instance).firstOrNull { it["id"] == request.stepId }
        if (current?.get("status") != WorkflowStatuses.WAITING_HUMAN) return deny("INTERACTION_STALE", "human_step_is_not_waiting")
        if (request.requestedStatus !in setOf(WorkflowStatuses.COMPLETED, WorkflowStatuses.FAILED)) {
            return deny("ILLEGAL_TRANSITION", "human_resolution_target_not_allowed")
        }
        if (request.requestedStatus == WorkflowStatuses.COMPLETED) {
            val decision = evidence.any {
                it.evidenceId in request.evidenceIds && it.kind == "human-decision" && it.outcome == "pass" &&
                    it.source?.get("kind") == "factory-human" && it.source.get("actorId") == execution.actorId
            }
            if (!decision) return deny("PASS_EVIDENCE_REQUIRED", "matching_human_decision_required", listOf("human-decision:pass"))
            val bridged = present.copy(
                instance = present.instance + (
                    "steps" to stepsOf(present.instance).map { step ->
                        if (step["id"] == request.stepId) step + ("status" to WorkflowStatuses.RUNNING) else step
                    }
                    ),
            )
            val evaluated = evaluateTransition(
                request.copy(requestedStatus = WorkflowStatuses.COMPLETED),
                bridged,
                definition,
                evidence,
                execution.copy(kind = "factory-human-resolution"),
            )
            return if (evaluated is TransitionDecision.Denied &&
                evaluated.code == "ACTOR_NOT_AUTHORIZED" &&
                evaluated.reason == "runtime_cannot_transition_step_responsibility"
            ) {
                TransitionDecision.Allowed
            } else {
                evaluated
            }
        }
        val completion = evaluateTransition(
            request.copy(requestedStatus = WorkflowStatuses.FAILED),
            present,
            definition,
            evidence,
            execution,
        )
        if (completion !is TransitionDecision.Allowed) return completion
        val selected = request.evidenceIds.mapNotNull { id -> evidence.firstOrNull { it.evidenceId == id } }
        return if (selected.any {
                it.kind == "human-decision" && it.outcome == "fail" &&
                    it.source?.get("kind") == "factory-human" && it.source.get("actorId") == execution.actorId
            }
        ) {
            TransitionDecision.Allowed
        } else {
            deny("FAIL_EVIDENCE_REQUIRED", "matching_human_decision_fail_required", listOf("human-decision:fail"))
        }
    }

    /** Port of `applyWorkflowTransition`: materializes the next snapshot. */
    fun applyTransition(
        snapshot: WorkflowSnapshot,
        definition: WorkflowPolicyDefinition,
        request: WorkflowTransitionRequest,
        observedAt: String = nowIso(),
    ): WorkflowSnapshot {
        val previous = LinkedHashMap<String, String>()
        stepsOf(snapshot.instance).forEach { step ->
            val id = step["id"] as? String
            val status = step["status"] as? String
            if (id != null && status != null) previous[id] = status
        }
        previous[request.stepId] = request.requestedStatus
        if (request.requestedStatus == WorkflowStatuses.COMPLETED) {
            for (step in definition.steps) {
                if (previous[step.id] == WorkflowStatuses.PENDING &&
                    step.dependsOn.all { previous[it] == WorkflowStatuses.COMPLETED }
                ) {
                    previous[step.id] = WorkflowStatuses.READY
                }
            }
        }
        val statuses = previous.values.toList()
        val status = when {
            statuses.all { it == WorkflowStatuses.COMPLETED } -> WorkflowStatuses.COMPLETED
            statuses.any { it == WorkflowStatuses.FAILED } -> WorkflowStatuses.FAILED
            statuses.any { it == WorkflowStatuses.WAITING_HUMAN } -> WorkflowStatuses.WAITING_HUMAN
            statuses.any { it == WorkflowStatuses.BLOCKED } -> WorkflowStatuses.BLOCKED
            statuses.any { it == WorkflowStatuses.RUNNING } -> WorkflowStatuses.RUNNING
            statuses.any { it == WorkflowStatuses.READY } -> WorkflowStatuses.READY
            else -> WorkflowStatuses.PENDING
        }
        val revision = snapshot.revision + 1
        val instance = snapshot.instance + mapOf(
            "revision" to revision,
            "status" to status,
            "steps" to stepsOf(snapshot.instance).map { it + ("status" to previous[it["id"]]) },
            "updatedAt" to observedAt,
        )
        val projection = snapshot.projection + mapOf(
            "status" to status,
            "steps" to projectionSteps(snapshot.projection).map { it + ("status" to previous[it["id"]]) },
        )
        return snapshot.copy(instance = instance, projection = projection, revision = revision)
    }

    /** Port of `applyHumanCheckpointOpen`: ready -> waiting_human for a human step. */
    fun applyHumanCheckpointOpen(
        snapshot: WorkflowSnapshot,
        request: WorkflowTransitionRequest,
        observedAt: String = nowIso(),
    ): WorkflowSnapshot {
        val statuses = LinkedHashMap<String, String>()
        stepsOf(snapshot.instance).forEach { step ->
            val id = step["id"] as? String
            val status = step["status"] as? String
            if (id != null && status != null) statuses[id] = status
        }
        statuses[request.stepId] = WorkflowStatuses.WAITING_HUMAN
        val revision = snapshot.revision + 1
        val instance = snapshot.instance + mapOf(
            "revision" to revision,
            "status" to WorkflowStatuses.WAITING_HUMAN,
            "steps" to stepsOf(snapshot.instance).map { it + ("status" to statuses[it["id"]]) },
            "updatedAt" to observedAt,
        )
        val projection = snapshot.projection + mapOf(
            "status" to WorkflowStatuses.WAITING_HUMAN,
            "steps" to projectionSteps(snapshot.projection).map { it + ("status" to statuses[it["id"]]) },
        )
        return snapshot.copy(instance = instance, projection = projection, revision = revision)
    }
}

/** ISO helper re-exported for the policy call sites. */
fun policyNowIso(): String = Instant.now().toString()
