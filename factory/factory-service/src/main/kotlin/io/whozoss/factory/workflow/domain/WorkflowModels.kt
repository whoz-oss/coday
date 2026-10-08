package io.whozoss.factory.workflow.domain

/**
 * Domain + persistence models of the workflow aggregate (A6).
 *
 * Vocabulary mirrors the Node sources
 * (`factory/src/domain/workflow/` and
 * `factory/src/adapters/persistence/sql/sql-workflow-*`):
 * `schemaVersion` values, statuses, evidence and interaction shapes are kept
 * byte-for-byte where they cross the wire.
 */

const val WORKFLOW_DEFINITION_SCHEMA_VERSION = "1"
const val WORKFLOW_GOVERNANCE_MODE = "governed"

/** Responsibility identifies the executor, never the produced artifact. */
enum class ResponsibilityKind(val wire: String) {
    HUMAN("human"),
    AGENT("agent"),
    CODE("code"),
    ;

    companion object {
        fun fromWire(value: String?): ResponsibilityKind? = entries.firstOrNull { it.wire == value }
    }
}

/** The allowed lifecycle statuses of a step / projection / instance. */
object WorkflowStatuses {
    const val PENDING = "pending"
    const val READY = "ready"
    const val RUNNING = "running"
    const val WAITING_HUMAN = "waiting_human"
    const val BLOCKED = "blocked"
    const val COMPLETED = "completed"
    const val FAILED = "failed"
    const val CANCELLED = "cancelled"

    val ALL: Set<String> = setOf(PENDING, READY, RUNNING, WAITING_HUMAN, BLOCKED, COMPLETED, FAILED, CANCELLED)

    /** Node `WORKFLOW_TRANSITIONS` state machine. */
    val TRANSITIONS: Map<String, Set<String>> = mapOf(
        PENDING to setOf(READY),
        READY to setOf(RUNNING, BLOCKED, FAILED, CANCELLED),
        RUNNING to setOf(WAITING_HUMAN, BLOCKED, COMPLETED, FAILED, CANCELLED),
        WAITING_HUMAN to setOf(RUNNING, BLOCKED, FAILED, CANCELLED),
        BLOCKED to setOf(READY, RUNNING, FAILED, CANCELLED),
        COMPLETED to emptySet(),
        FAILED to emptySet(),
        CANCELLED to emptySet(),
    )

    /**
     * Terminal run statuses of a workflow instance (Phase 10): once a
     * governed instance reaches one of them it is SEALED — final and
     * immutable. Reopening is strictly forbidden ([TRANSITIONS] gives them an
     * empty outgoing set, and the service layer rejects any further mutation
     * with `WORKFLOW_SEALED`); resuming or re-running the requirement happens
     * via a NEW workflow linked to the sealed predecessor (see
     * `linkedWorkflowRelations` in `WorkflowInstance.kt`).
     */
    val TERMINAL: Set<String> = setOf(COMPLETED, FAILED, CANCELLED)

    /** Whether a status is a valid wire status. */
    fun isKnown(value: String?): Boolean = value != null && value in ALL

    /** Whether [status] is a terminal (sealed) run status of a workflow instance. */
    fun isTerminal(status: String?): Boolean = status != null && status in TERMINAL
}

/** Responsibility of a workflow step (`kind` + optional display `name`). */
data class WorkflowStepResponsibility(
    val kind: ResponsibilityKind,
    val name: String? = null,
) {
    fun toJson(): Map<String, Any?> = buildMap {
        put("kind", kind.wire)
        if (name != null) put("name", name)
    }
}

/** One step of a workflow definition. */
data class WorkflowStepDefinition(
    val id: String,
    val name: String,
    val responsibility: WorkflowStepResponsibility,
    val dependsOn: List<String>,
)

/** The resolved identity of a workflow definition used to hash a start command. */
data class WorkflowDefinitionInput(
    val workflowType: String,
    val version: String,
    val definitionHash: String,
    val steps: List<WorkflowStepDefinition>,
)

/** A validated workflow definition aggregate as persisted. */
data class WorkflowDefinitionRecord(
    val workflowType: String,
    val version: String,
    val definitionHash: String,
    val definition: Map<String, Any?>,
    val schemaVersion: String = WORKFLOW_DEFINITION_SCHEMA_VERSION,
)

/** Trusted attribution captured by Factory for the engineer's launch request. */
data class ControllerRequestInput(
    val text: String,
    val namespaceId: String,
    val observedAt: String,
    val actorId: String,
    val source: String,
) {
    fun toJson(): Map<String, Any?> = linkedMapOf(
        "text" to text,
        "observedAt" to observedAt,
        "actorId" to actorId,
        "source" to source,
        "namespaceId" to namespaceId,
    )
}

/** `POST /workflows/{id}/start` command. */
data class WorkflowStartCommand(
    val workflowId: String,
    val workflowType: String,
    val title: String,
    val relations: Map<String, Any?>? = null,
    /** Optional Jira/issue ticket carried through the session (brief, branch naming, relations). */
    val ticket: String? = null,
    val controllerRequest: ControllerRequestInput? = null,
)

/** The trusted controlling runtime of a governed workflow. */
data class ControllerExecutionInput(
    val runtimeId: String,
    val kind: String,
    val agentId: String,
    val caseId: String? = null,
    val actorId: String? = null,
    val threadId: String? = null,
    val observedAt: String? = null,
    /** Namespace attribution of the trusted execution; never serialized into `controllerExecution`. */
    val namespaceId: String? = null,
) {
    fun toJson(): Map<String, Any?> = buildMap {
        put("runtimeId", runtimeId)
        put("kind", kind)
        put("agentId", agentId)
        if (caseId != null) put("caseId", caseId)
        if (actorId != null) put("actorId", actorId)
        if (threadId != null) put("threadId", threadId)
        if (observedAt != null) put("observedAt", observedAt)
    }
}

/** Execution attribution of a trusted Factory control-plane call. */
data class WorkflowExecution(
    val kind: String,
    val runtimeId: String,
    val agentId: String? = null,
    val actorId: String? = null,
    val caseId: String? = null,
    val threadId: String? = null,
    val namespaceId: String? = null,
)

/** The persisted governed instance + its projection. */
data class WorkflowInstanceRecord(
    val namespaceId: String,
    val workflowId: String,
    val revision: Int,
    val status: String,
    val creationCommandHash: String?,
    val instance: Map<String, Any?>,
    val projection: Map<String, Any?>,
    /**
     * Root case id of the durable case family of this execution run (Lot B).
     * Reserved atomically once, before any remote agent call, and shared by
     * every durable attempt of the run. `null` on legacy instances that predate
     * the case family — a legacy run is never converted (strict compatibility).
     */
    val rootCaseId: String? = null,
)

/** The declarative WorkflowProjection v1/v2 store row. */
data class WorkflowProjectionRecord(
    val namespaceId: String,
    val workflowId: String,
    val schemaVersion: String,
    val revision: Int,
    val projectionHash: String,
    val status: String,
    val projection: Map<String, Any?>,
    val instance: Map<String, Any?>?,
    val governanceMode: String?,
    val definitionVersion: String?,
    val definitionHash: String?,
    val relations: Map<String, Any?>?,
    val controllerExecution: Map<String, Any?>?,
    val lifecycleState: String,
    /**
     * Audit timestamp: when the projection node was first written to Neo4j.
     * Preserved across all subsequent updates by [Neo4jWorkflowRepository.publishProjection].
     * Null when the projection was loaded via a path that does not propagate it
     * (e.g. older code paths before this field was added to the domain).
     */
    val createdAt: java.time.Instant? = null,
)

/** A validated `POST /transitions` / `POST /code-transitions` request. */
data class WorkflowTransitionRequest(
    val requestId: String,
    val workflowId: String,
    val stepId: String,
    val expectedRevision: Int,
    val requestedStatus: String,
    val evidenceIds: List<String>,
    val idempotencyKey: String? = null,
)

/** An append-only evidence item (`workflow_evidence`). */
data class WorkflowEvidenceItem(
    val evidenceId: String,
    val namespaceId: String,
    val workflowId: String,
    val stepId: String?,
    val kind: String,
    val outcome: String?,
    val source: Map<String, Any?>?,
    val facts: Map<String, Any?>,
    val idempotencyKey: String?,
    val createdAt: String?,
)

/** A human interaction awaiting a decision (`human_interactions`). */
data class HumanInteractionRecord(
    val interactionId: String,
    val namespaceId: String,
    val workflowId: String,
    val stepId: String,
    val interactionType: String,
    val status: String,
    val revision: Int,
    val payload: Map<String, Any?>,
)

/** An append-only human interaction lifecycle event (`human_interaction_events`). */
data class HumanInteractionEventRecord(
    val eventId: String,
    val interactionId: String,
    val eventType: String,
    val actorId: String,
    val payload: Map<String, Any?>,
)

/**
 * Per-step execution state of a workflow instance (`workflow_step_states`, V3).
 *
 * The status vocabulary is [WorkflowStatuses] and the sequencer (W8.3) is the
 * only writer of the DAG lifecycle: `pending -> ready -> running -> completed |
 * failed | blocked | waiting_human`. `revision` is the optimistic-locking counter
 * and `payload` the verbatim JSONB trace of the step (facts of the last attempt).
 */
data class WorkflowStepStateRecord(
    val namespaceId: String,
    val workflowId: String,
    val stepId: String,
    val revision: Int,
    val status: String,
    val payload: Map<String, Any?> = emptyMap(),
    val createdAt: String? = null,
    val updatedAt: String? = null,
)

/** A workflow code transition row (`workflow_code_transitions`). */
data class WorkflowCodeTransitionRecord(
    val codeTransitionId: String,
    val stepId: String,
    val outcome: String,
    val exitCode: Int?,
    val payload: Map<String, Any?>,
    val createdAt: String?,
)

/** Timing aggregates of one workflow. */
data class WorkflowTimingMetrics(
    val workflowId: String,
    val namespaceId: String,
    val activeMs: Long,
    val waitingHumanMs: Long,
    val blockedMs: Long,
    val attempts: Int,
    val startedAt: String?,
    val firstCompletedAt: String?,
    val lastCompletedAt: String?,
    val complete: Boolean,
    val incompleteReasons: List<String>,
)

/** Retry counters of one workflow. */
data class WorkflowRetriesMetrics(
    val workflowId: String,
    val namespaceId: String,
    val retries: Int,
    val blockedSteps: List<String>,
    val openRetryInteractions: Int,
)

/** Aggregated operational metrics for a workflow (self or descendants). */
data class WorkflowAggregatedMetrics(
    val namespaceId: String,
    val workflowId: String,
    val scope: String,
    val observedAt: String,
    val timing: WorkflowTimingMetrics,
    val retries: WorkflowRetriesMetrics,
    val evidenceCount: Int,
    val interactionCount: Int,
)
