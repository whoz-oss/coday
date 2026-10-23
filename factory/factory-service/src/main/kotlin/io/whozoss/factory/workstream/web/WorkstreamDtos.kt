package io.whozoss.factory.workstream.web

import java.time.Instant

/**
 * Strict bounds applied to every collection the workstream surface accepts or
 * returns. Incoming limits are coerced into `[1, MAX_LIMIT]`; every response
 * list is capped and reports `truncated` when items were dropped.
 */
object WorkstreamBounds {
    const val MAX_LIMIT = 50
    const val DEFAULT_LIMIT = 20
    const val MAX_WORKFLOWS_SCANNED = 50

    /** Coerce a caller-supplied limit into the strict `[1, MAX_LIMIT]` range. */
    fun boundedLimit(requested: Int?): Int = (requested ?: DEFAULT_LIMIT).coerceIn(1, MAX_LIMIT)
}

/** Enriched workstream creation request (registry fields are optional). */
data class CreateWorkstreamRequest(
    val slug: String? = null,
    val name: String? = null,
    /** Alias of [name]; exactly one of the two must be supplied. */
    val title: String? = null,
    val status: String? = null,
    val namespaceId: String? = null,
    val controllerAgentRef: String? = null,
    val allowedWorkflowTypes: List<String>? = null,
    val governancePolicyRef: String? = null,
)

/**
 * Workstream update request. `null` fields are left untouched;
 * [expectedRevision] is the optional optimistic-locking precondition.
 */
data class UpdateWorkstreamRequest(
    val name: String? = null,
    /** Alias of [name]. */
    val title: String? = null,
    val status: String? = null,
    val namespaceId: String? = null,
    val controllerAgentRef: String? = null,
    val allowedWorkflowTypes: List<String>? = null,
    val governancePolicyRef: String? = null,
    val expectedRevision: Int? = null,
)

/** A bounded collection: the full [count], the capped [items], and whether items were dropped. */
data class WorkstreamSection<T>(
    val count: Int,
    val items: List<T>,
    val truncated: Boolean,
)

/** Compact, secret-free summary of an active workflow. */
data class WorkflowSummary(
    val workflowId: String,
    val namespaceId: String?,
    val workflowType: String?,
    val title: String?,
    val status: String?,
    val revision: Int,
)

/** Bounded view of the step statuses that need operator attention. */
data class StepCounts(
    val running: Int,
    val waitingHuman: Int,
    val blocked: Int,
    val items: List<StepSummary>,
    val truncated: Boolean,
)

/** One step of interest (running / waiting_human / blocked). */
data class StepSummary(
    val workflowId: String,
    val stepId: String,
    val name: String?,
    val status: String,
)

/** Compact summary of a durable agent attempt (mirrors the runtime-independent DTO). */
data class AttemptSummary(
    val attemptId: String,
    val workflowId: String,
    val stepId: String,
    val agentName: String,
    val status: String,
    val revision: Int,
    val createdAt: Instant? = null,
    val completedAt: Instant? = null,
)

/** One open human interaction awaiting a decision. */
data class HumanActionSummary(
    val interactionId: String,
    val workflowId: String,
    val stepId: String?,
    val interactionType: String?,
    val status: String?,
)

/** One failed oracle execution. */
data class OracleFailureSummary(
    val executionId: String,
    val oracleId: String?,
    val workflowId: String?,
    val namespaceId: String?,
    val updatedAt: String? = null,
)

/** Bounded environments view with a lifecycle-state breakdown. */
data class EnvironmentSection(
    val count: Int,
    val byState: Map<String, Int>,
    val items: List<EnvironmentSummary>,
    val truncated: Boolean,
)

/** Compact summary of a work environment. */
data class EnvironmentSummary(
    val environmentId: String,
    val workflowId: String,
    val namespaceId: String,
    val lifecycleState: String,
    val revision: Int,
    val createdAt: Instant? = null,
)

/** One recent change observed while aggregating, newest first. */
data class ChangeSummary(
    val kind: String,
    val refId: String,
    val workflowId: String? = null,
    val timestamp: String,
)

/**
 * Read-only aggregated projection of a workstream: live counts and bounded
 * summaries composed from the workflow, attempt, oracle and environment
 * aggregates, plus the stable [workstreamRevision] ETag of the whole view.
 */
data class WorkstreamProjectionResponse(
    val workstreamId: String,
    val namespaceId: String?,
    val status: String,
    val workstreamRevision: String,
    val activeWorkflows: WorkstreamSection<WorkflowSummary>,
    val steps: StepCounts,
    val attempts: WorkstreamSection<AttemptSummary>,
    val humanActions: WorkstreamSection<HumanActionSummary>,
    val failedOracles: WorkstreamSection<OracleFailureSummary>,
    val environments: EnvironmentSection,
    val recentChanges: WorkstreamSection<ChangeSummary>,
    val boundaryViolations: Int,
)
