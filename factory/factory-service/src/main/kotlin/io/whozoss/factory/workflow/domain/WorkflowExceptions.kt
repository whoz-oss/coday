package io.whozoss.factory.workflow.domain

import io.whozoss.factory.error.FactoryException

/**
 * Machine-readable error codes of the workflow aggregate.
 *
 * Vocabulary mirrors the Node workflow domain and the SQL adapters
 * (`workflow-projection-store.mjs`, `workflow-instance-repository.ts`,
 * `workflow-evidence-store.mjs`, `workflow-human-interaction-store.mjs`) so a
 * client keeps the exact same error semantics across the Node and Kotlin
 * control planes.
 */
object WorkflowErrorCodes {
    const val INVALID_REQUEST = "INVALID_REQUEST"
    const val INVALID_EXECUTION = "INVALID_EXECUTION"
    const val INVALID_NAMESPACE_ID = "INVALID_NAMESPACE_ID"
    const val INVALID_WORKFLOW_ID = "INVALID_WORKFLOW_ID"
    const val INVALID_START_REQUEST = "INVALID_START_REQUEST"
    const val INVALID_PROJECTION = "INVALID_PROJECTION"
    const val INVALID_TRANSITION_REQUEST = "INVALID_TRANSITION_REQUEST"
    const val UNTRUSTED_REQUEST_ID = "UNTRUSTED_REQUEST_ID"
    const val UNTRUSTED_WORKFLOW_INPUT = "UNTRUSTED_WORKFLOW_INPUT"
    const val WORKFLOW_ID_MISMATCH = "WORKFLOW_ID_MISMATCH"
    const val WORKFLOW_NOT_FOUND = "WORKFLOW_NOT_FOUND"
    const val WORKFLOW_REMOVED = "WORKFLOW_REMOVED"
    const val WORKFLOW_PURGED = "WORKFLOW_PURGED"
    const val WORKFLOW_ALREADY_EXISTS = "WORKFLOW_ALREADY_EXISTS"
    const val WORKFLOW_IDENTITY_CONFLICT = "WORKFLOW_IDENTITY_CONFLICT"
    const val WORKFLOW_DEFINITION_NOT_FOUND = "WORKFLOW_DEFINITION_NOT_FOUND"
    const val WORKFLOW_DEFINITION_INVALID = "WORKFLOW_DEFINITION_INVALID"
    const val WORKFLOW_DEFINITION_AMBIGUOUS = "WORKFLOW_DEFINITION_AMBIGUOUS"
    const val WORKFLOW_DEFINITION_MISMATCH = "WORKFLOW_DEFINITION_MISMATCH"

    /**
     * 400 — the workflow definition declares an execution plugin
     * (`execution.plugin`) that is absent from the PF4J plugin manager or not
     * started. The run is refused before any persistence or external effect.
     */
    const val WORKFLOW_EXECUTION_PLUGIN_NOT_FOUND = "WORKFLOW_EXECUTION_PLUGIN_NOT_FOUND"
    const val WORKFLOW_NOT_GOVERNED = "WORKFLOW_NOT_GOVERNED"
    const val DECLARATIVE_WORKFLOW = "DECLARATIVE_WORKFLOW"
    const val GOVERNED_WORKFLOW_REQUIRES_TRANSITION = "GOVERNED_WORKFLOW_REQUIRES_TRANSITION"
    const val REVISION_CONFLICT = "REVISION_CONFLICT"

    /**
     * 409 — Phase 10 terminal governance: the overall run status of the
     * governed instance is terminal (`completed` / `failed` / `cancelled`),
     * so the workflow is SEALED. Reopening or mutating a sealed workflow is
     * strictly forbidden; resuming the requirement requires a NEW workflow
     * linked to the sealed predecessor.
     */
    const val WORKFLOW_SEALED = "WORKFLOW_SEALED"
    const val INVALID_LIFECYCLE_TRANSITION = "INVALID_LIFECYCLE_TRANSITION"
    const val WORKFLOW_STORAGE_FAILURE = "WORKFLOW_STORAGE_FAILURE"
    const val EVIDENCE_STORAGE_FAILURE = "EVIDENCE_STORAGE_FAILURE"
    const val HUMAN_INTERACTION_FAILURE = "HUMAN_INTERACTION_FAILURE"
    const val FACTORY_ONLY_EVIDENCE = "FACTORY_ONLY_EVIDENCE"
    const val UNKNOWN_STEP = "UNKNOWN_STEP"
    const val INVALID_EVIDENCE = "INVALID_EVIDENCE"
    const val IDEMPOTENCY_KEY_COLLISION = "IDEMPOTENCY_KEY_COLLISION"
    const val INTERACTION_NOT_FOUND = "INTERACTION_NOT_FOUND"
    const val INTERACTION_STALE = "INTERACTION_STALE"
    const val INTERACTION_SCOPE_MISMATCH = "INTERACTION_SCOPE_MISMATCH"
    const val INVALID_INTERACTION = "INVALID_INTERACTION"
    const val INVALID_REPLY = "INVALID_REPLY"
    const val ACTION_NOT_ALLOWED = "ACTION_NOT_ALLOWED"
    const val UNAUTHENTICATED_ACTOR = "UNAUTHENTICATED_ACTOR"
    const val TRUST_CONTEXT_UNAVAILABLE = "TRUST_CONTEXT_UNAVAILABLE"
    const val ROUTE_NOT_FOUND = "ROUTE_NOT_FOUND"
    const val UNSUPPORTED_STATE = "UNSUPPORTED_STATE"
    const val METRICS_DATA_INCOMPLETE = "METRICS_DATA_INCOMPLETE"
}

/**
 * Workflow failure carrying a stable machine code.
 *
 * Extends [FactoryException] so the canonical
 * `{ "error": { code, message, details } }` envelope is rendered by the shared
 * `FactoryExceptionHandler`, exactly like the Node workflow control plane.
 */
class WorkflowException(
    errorCode: String,
    message: String = errorCode,
    statusCode: Int = 409,
    details: Any? = null,
    cause: Throwable? = null,
) : FactoryException(statusCode, errorCode, message, details, cause)

/** Maps a machine code to the HTTP status the Node control plane returns. */
fun workflowStatusCode(code: String): Int = when (code) {
    WorkflowErrorCodes.INVALID_REQUEST,
    WorkflowErrorCodes.INVALID_EXECUTION,
    WorkflowErrorCodes.INVALID_NAMESPACE_ID,
    WorkflowErrorCodes.INVALID_WORKFLOW_ID,
    WorkflowErrorCodes.INVALID_START_REQUEST,
    WorkflowErrorCodes.WORKFLOW_EXECUTION_PLUGIN_NOT_FOUND,
    WorkflowErrorCodes.INVALID_PROJECTION,
    WorkflowErrorCodes.INVALID_TRANSITION_REQUEST,
    WorkflowErrorCodes.UNTRUSTED_REQUEST_ID,
    WorkflowErrorCodes.UNTRUSTED_WORKFLOW_INPUT,
    WorkflowErrorCodes.WORKFLOW_ID_MISMATCH,
    WorkflowErrorCodes.INVALID_LIFECYCLE_TRANSITION,
    WorkflowErrorCodes.UNKNOWN_STEP,
    WorkflowErrorCodes.INVALID_EVIDENCE,
    WorkflowErrorCodes.INVALID_INTERACTION,
    WorkflowErrorCodes.INVALID_REPLY,
    WorkflowErrorCodes.ACTION_NOT_ALLOWED,
    WorkflowErrorCodes.WORKFLOW_DEFINITION_INVALID,
    WorkflowDefinitionErrorCodes.INVALID_DEFINITION,
    WorkflowDefinitionErrorCodes.INVALID_SCHEMA_VERSION,
    WorkflowDefinitionErrorCodes.INVALID_VALUE,
    WorkflowDefinitionErrorCodes.DUPLICATE_STEP_ID,
    WorkflowDefinitionErrorCodes.MISSING_DEPENDENCY,
    WorkflowDefinitionErrorCodes.SELF_DEPENDENCY,
    WorkflowDefinitionErrorCodes.DEPENDENCY_CYCLE,
    WorkflowDefinitionErrorCodes.INVALID_RESPONSIBILITY,
    WorkflowErrorCodes.UNSUPPORTED_STATE,
    -> 400
    WorkflowErrorCodes.TRUST_CONTEXT_UNAVAILABLE,
    WorkflowErrorCodes.UNAUTHENTICATED_ACTOR,
    -> 401
    WorkflowErrorCodes.FACTORY_ONLY_EVIDENCE -> 403
    WorkflowErrorCodes.WORKFLOW_NOT_FOUND,
    WorkflowErrorCodes.WORKFLOW_DEFINITION_NOT_FOUND,
    WorkflowErrorCodes.INTERACTION_NOT_FOUND,
    WorkflowErrorCodes.ROUTE_NOT_FOUND,
    -> 404
    WorkflowErrorCodes.WORKFLOW_REMOVED,
    WorkflowErrorCodes.WORKFLOW_PURGED,
    -> 410
    WorkflowErrorCodes.METRICS_DATA_INCOMPLETE -> 422
    // Explicit: a sealed (terminal-run) workflow rejects any further mutation
    // as a conflict, like every other optimistic-locking / state-machine code.
    WorkflowErrorCodes.WORKFLOW_SEALED -> 409
    else -> 409
}

/** Builds a [WorkflowException] for a machine code, choosing the Node HTTP status. */
fun workflowException(code: String, message: String = code, details: Any? = null): WorkflowException =
    WorkflowException(code, message, workflowStatusCode(code), details)
