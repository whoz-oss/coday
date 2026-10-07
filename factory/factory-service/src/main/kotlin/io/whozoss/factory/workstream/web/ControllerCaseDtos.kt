package io.whozoss.factory.workstream.web

import io.whozoss.factory.workstream.domain.ControllerCaseExecution

/**
 * Request to start the first controller case of a workstream. [caseId] is
 * optional; a UUID is generated when absent.
 */
data class StartControllerCaseRequest(
    val caseId: String? = null,
)

/**
 * Request to explicitly compact / renew the controller case of a workstream.
 * [caseId] is optional (UUID generated when absent); [compactionReason] is
 * the optional operator-supplied reason archived on the outgoing case.
 */
data class CompactControllerCaseRequest(
    val caseId: String? = null,
    val compactionReason: String? = null,
)

/**
 * Cockpit view of one controller case execution. The full [contextSummary]
 * JSON blob is deliberately *not* echoed here: the live bounded package is
 * exposed by the dedicated `GET .../controller-case/context` endpoint.
 */
data class ControllerCaseResponse(
    val workstreamId: String,
    val caseId: String,
    val controllerAgentRef: String,
    val status: String,
    val sequence: Int,
    val startedAt: String,
    val archivedAt: String? = null,
    val compactionReason: String? = null,
    val contextRevision: String? = null,
)

/** Map a domain [ControllerCaseExecution] to its Cockpit [ControllerCaseResponse]. */
fun ControllerCaseExecution.toResponse(): ControllerCaseResponse =
    ControllerCaseResponse(
        workstreamId = workstreamId,
        caseId = caseId,
        controllerAgentRef = controllerAgentRef,
        status = status.dbValue,
        sequence = sequence,
        startedAt = startedAt.toString(),
        archivedAt = archivedAt?.toString(),
        compactionReason = compactionReason,
        contextRevision = contextRevision,
    )

/** The controller case history of a workstream: the active case plus every archived one, ordered by sequence. */
data class ControllerCaseHistoryResponse(
    val workstreamId: String,
    val activeCaseId: String?,
    val cases: List<ControllerCaseResponse>,
)

/**
 * Bounded resumption context package ("paquet de reprise borné") rebuilt from
 * the Phase 5 aggregated projection when a controller case starts or is
 * compacted into a fresh one.
 *
 * It summarizes the key active workflows, open human interactions, blockers
 * and recent changes within strict bounds
 * ([io.whozoss.factory.workstream.domain.ControllerCaseBounds]) — it NEVER
 * carries raw conversation histories. [sourceRevision] is the projection
 * `workstreamRevision` ETag the package was derived from (provenance).
 */
data class ControllerResumptionPackage(
    val workstreamId: String,
    val sourceRevision: String,
    val counts: ControllerContextCounts,
    val activeWorkflows: List<ContextWorkflow>,
    val openHumanInteractions: List<ContextHumanAction>,
    val blockers: List<ContextBlocker>,
    val recentChanges: List<ContextChange>,
)

/** Live section counts of the projection the package was built from (authoritative, uncapped). */
data class ControllerContextCounts(
    val activeWorkflows: Int,
    val running: Int,
    val waitingHuman: Int,
    val blocked: Int,
    val attempts: Int,
    val humanActions: Int,
    val failedOracles: Int,
    val environments: Int,
)

/** Compact, secret-free summary of an active workflow. */
data class ContextWorkflow(
    val workflowId: String,
    val workflowType: String?,
    val title: String?,
    val status: String?,
)

/** Compact summary of one open human interaction awaiting a decision. */
data class ContextHumanAction(
    val interactionId: String,
    val workflowId: String,
    val interactionType: String?,
    val status: String?,
)

/** One blocker: a `blocked` step or a failed oracle execution. */
data class ContextBlocker(
    val kind: String,
    val refId: String,
    val workflowId: String?,
)

/** One recent change observed on the workstream, newest first. */
data class ContextChange(
    val kind: String,
    val refId: String,
    val workflowId: String? = null,
    val timestamp: String,
)
