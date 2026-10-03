package io.whozoss.factory.workflow.persistence

import io.whozoss.factory.persistence.TenantScope
import io.whozoss.factory.workflow.domain.HumanInteractionEventRecord
import io.whozoss.factory.workflow.domain.HumanInteractionRecord
import io.whozoss.factory.workflow.domain.WorkflowCodeTransitionRecord
import io.whozoss.factory.workflow.domain.WorkflowDefinitionRecord
import io.whozoss.factory.workflow.domain.WorkflowEvidenceItem
import io.whozoss.factory.workflow.domain.WorkflowInstanceRecord
import io.whozoss.factory.workflow.domain.WorkflowProjectionRecord
import io.whozoss.factory.workflow.domain.WorkflowStepStateRecord
import io.whozoss.factory.workflow.domain.WorkflowTransitionRequest

/** Outcome of a declarative projection publication. */
sealed interface ProjectionPublishResult {
    data class Changed(val record: WorkflowProjectionRecord) : ProjectionPublishResult
    data class Idempotent(val record: WorkflowProjectionRecord) : ProjectionPublishResult
    data class Conflict(val code: String) : ProjectionPublishResult
}

/** Outcome of an evidence append; an identical replay is a no-op. */
sealed interface EvidenceAppendResult {
    data class Created(val item: WorkflowEvidenceItem) : EvidenceAppendResult
    data class Idempotent(val item: WorkflowEvidenceItem) : EvidenceAppendResult
    data class Collision(val code: String) : EvidenceAppendResult
}

/**
 * Persistence port of the workflow aggregate.
 *
 * Every read/write is scoped by the composite tenant key
 * `(organizationId, workstreamId, namespaceId, workflowId)`. The port never
 * exposes SQL; the append-only evidence log and the human-interaction journal
 * are separate ports ([WorkflowEvidenceRepository], [HumanInteractionRepository])
 * so their invariants are explicit.
 */
interface WorkflowRepository {

    // ----- definitions ----------------------------------------------------
    fun findDefinition(scope: TenantScope, workflowType: String, version: String): WorkflowDefinitionRecord?

    fun listDefinitions(scope: TenantScope): List<WorkflowDefinitionRecord>

    fun saveDefinition(scope: TenantScope, record: WorkflowDefinitionRecord)

    /** Deletes a definition by `(workflowType, version)`; `false` when absent. */
    fun deleteDefinition(scope: TenantScope, workflowType: String, version: String): Boolean

    // ----- governed instances --------------------------------------------
    fun findInstance(scope: TenantScope, namespaceId: String, workflowId: String): WorkflowInstanceRecord?

    fun listInstances(scope: TenantScope, namespaceId: String): List<WorkflowInstanceRecord>

    /** Idempotent insert keyed by `(scope, namespace, workflowId, creationCommandHash)`. */
    fun insertInstance(scope: TenantScope, record: WorkflowInstanceRecord): WorkflowInstanceRecord

    /** Compare-and-swap the instance state at [expectedRevision]; `false` on stale revision. */
    fun updateInstance(
        scope: TenantScope,
        namespaceId: String,
        workflowId: String,
        expectedRevision: Int,
        next: WorkflowInstanceRecord,
    ): Boolean

    fun setInstanceStatus(scope: TenantScope, namespaceId: String, workflowId: String, from: String, to: String): Boolean

    fun deleteInstance(scope: TenantScope, namespaceId: String, workflowId: String): Boolean

    // ----- transition logs -----------------------------------------------
    fun appendTransition(
        scope: TenantScope,
        namespaceId: String,
        workflowId: String,
        transitionId: String,
        request: WorkflowTransitionRequest,
        fromStepId: String?,
        toStepId: String,
        payload: Map<String, Any?>,
    )

    fun listTransitionTimestamps(scope: TenantScope, namespaceId: String, workflowId: String): List<String>

    // ----- per-step DAG state ---------------------------------------------
    /** All step states of an instance, in insertion order. */
    fun findStepStates(scope: TenantScope, namespaceId: String, workflowId: String): List<WorkflowStepStateRecord>

    /** Insert or update a step state; on conflict the revision is incremented. */
    fun upsertStepState(scope: TenantScope, record: WorkflowStepStateRecord): WorkflowStepStateRecord

    /** Compare-and-swap a step status at [expectedRevision]; `false` on stale revision. */
    fun updateStepStatus(
        scope: TenantScope,
        namespaceId: String,
        workflowId: String,
        stepId: String,
        expectedRevision: Int,
        nextStatus: String,
        payload: Map<String, Any?>,
    ): Boolean

    /**
     * Atomic step claim: transitions the step to `running` only when its current
     * status is one of [fromStatuses], incrementing its revision and replacing its
     * payload in the same statement. Returns `false` when the step is not
     * claimable (already claimed, terminal, or absent), so two racing runs cannot
     * both own it.
     */
    fun claimStep(
        scope: TenantScope,
        namespaceId: String,
        workflowId: String,
        stepId: String,
        fromStatuses: List<String>,
        payload: Map<String, Any?>,
    ): Boolean

    fun appendCodeTransition(
        scope: TenantScope,
        namespaceId: String,
        workflowId: String,
        record: WorkflowCodeTransitionRecord,
    )

    fun listCodeTransitions(scope: TenantScope, namespaceId: String, workflowId: String): List<WorkflowCodeTransitionRecord>

    // ----- declarative projection store ----------------------------------
    fun findProjection(scope: TenantScope, namespaceId: String, workflowId: String): WorkflowProjectionRecord?

    /**
     * Lists the projections of a scope, optionally filtered by namespace.
     *
     * A `null`/blank [namespaceId] lists every namespace of the scope; a
     * supplied namespace filters to that one. The tenant key is always applied.
     */
    fun listProjections(scope: TenantScope, namespaceId: String?, lifecycleState: String): List<WorkflowProjectionRecord>

    /** Publication with optional optimistic-locking [expectedRevision] (0 = create). */
    fun publishProjection(
        scope: TenantScope,
        record: WorkflowProjectionRecord,
        expectedRevision: Int?,
    ): ProjectionPublishResult

    fun setProjectionLifecycle(
        scope: TenantScope,
        namespaceId: String,
        workflowId: String,
        from: List<String>,
        to: String,
    ): Boolean

    fun deleteProjectionLifecycle(scope: TenantScope, namespaceId: String, workflowId: String): Boolean
}

/** Port of the append-only evidence log (`workflow_evidence`). */
interface WorkflowEvidenceRepository {
    fun list(scope: TenantScope, namespaceId: String, workflowId: String, stepId: String? = null): List<WorkflowEvidenceItem>

    fun append(
        scope: TenantScope,
        namespaceId: String,
        workflowId: String,
        item: WorkflowEvidenceItem,
    ): EvidenceAppendResult
}

/** Port of the human-interaction aggregate (`human_interactions` + `human_interaction_events`). */
interface HumanInteractionRepository {
    fun find(scope: TenantScope, namespaceId: String, workflowId: String, interactionId: String): HumanInteractionRecord?

    fun list(scope: TenantScope, namespaceId: String, workflowId: String, openOnly: Boolean): List<HumanInteractionRecord>

    fun insert(scope: TenantScope, record: HumanInteractionRecord): HumanInteractionRecord

    /** Optimistic-locking update of an interaction; `false` on stale revision. */
    fun update(
        scope: TenantScope,
        namespaceId: String,
        workflowId: String,
        interactionId: String,
        expectedRevision: Int,
        next: HumanInteractionRecord,
    ): Boolean

    fun appendEvent(scope: TenantScope, namespaceId: String, workflowId: String, event: HumanInteractionEventRecord)

    fun listEvents(scope: TenantScope, namespaceId: String, workflowId: String): List<HumanInteractionEventRecord>
}
