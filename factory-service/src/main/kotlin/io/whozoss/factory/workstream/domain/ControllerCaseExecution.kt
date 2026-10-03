package io.whozoss.factory.workstream.domain

import io.whozoss.factory.workstream.persistence.ControllerCaseExecutionNode
import java.time.Instant

/**
 * Immutable historical record of one controller case bound to a workstream
 * over its lifetime (Phase 9).
 *
 * A controller case execution links one conversational case ([caseId]) to the
 * stable Workstream Agent identity ([controllerAgentRef]) of its workstream.
 * At most one execution is [ControllerCaseStatus.ACTIVE] per workstream;
 * compacting archives the current case and binds a fresh one ([sequence] +
 * 1) while the agent identity and the workstream identity are preserved —
 * the Workstream Agent remains a single stable conceptual interlocutor
 * without requiring an eternal case.
 *
 * [compactionReason] records why *this* case ended (null while active and for
 * a case that was never compacted away). [contextSummary] is the bounded
 * resumption context package (JSON, see [ControllerCaseBounds]) captured at
 * start time, [contextRevision] the projection ETag it was derived from.
 *
 * This record is distinct from worker cases and durable attempts: it governs
 * only the conversational interlocution with the Workstream Agent and holds
 * no lease over any workflow, attempt, oracle or environment.
 */
data class ControllerCaseExecution(
    val organizationId: String,
    val workstreamId: String,
    /** The controller case identifier (unique within the workstream). */
    val caseId: String,
    /** The stable agent identity preserved across cases of this workstream. */
    val controllerAgentRef: String,
    val status: ControllerCaseStatus = ControllerCaseStatus.ACTIVE,
    /** 1-based ordinal of this case within the workstream (1st, 2nd, …). */
    val sequence: Int,
    val startedAt: Instant,
    val archivedAt: Instant? = null,
    /** Why this case was archived / why the next case was created. */
    val compactionReason: String? = null,
    /** Bounded resumption context package (JSON string) captured at start. */
    val contextSummary: String? = null,
    /** The projection `workstreamRevision` ETag the package was built from. */
    val contextRevision: String? = null,
    val createdAt: Instant = Instant.now(),
    val updatedAt: Instant = Instant.now(),
)

/** Map a persisted [ControllerCaseExecutionNode] to its domain [ControllerCaseExecution]. */
fun ControllerCaseExecutionNode.toDomain(): ControllerCaseExecution =
    ControllerCaseExecution(
        organizationId = organizationId,
        workstreamId = workstreamId,
        caseId = caseId,
        controllerAgentRef = controllerAgentRef,
        status = ControllerCaseStatus.fromDbValue(status),
        sequence = sequence,
        startedAt = startedAt,
        archivedAt = archivedAt,
        compactionReason = compactionReason,
        contextSummary = contextSummary,
        contextRevision = contextRevision,
        createdAt = createdAt,
        updatedAt = updatedAt,
    )

/** Map a domain [ControllerCaseExecution] to its persistable [ControllerCaseExecutionNode]. */
fun ControllerCaseExecution.toNode(): ControllerCaseExecutionNode =
    ControllerCaseExecutionNode(
        id = ControllerCaseExecutionNode.compositeId(organizationId, workstreamId, caseId),
        organizationId = organizationId,
        workstreamId = workstreamId,
        caseId = caseId,
        controllerAgentRef = controllerAgentRef,
        status = status.dbValue,
        sequence = sequence,
        startedAt = startedAt,
        archivedAt = archivedAt,
        compactionReason = compactionReason,
        contextSummary = contextSummary,
        contextRevision = contextRevision,
        createdAt = createdAt,
        updatedAt = updatedAt,
    )
