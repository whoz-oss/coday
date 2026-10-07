package io.whozoss.factory.workstream.domain

import io.whozoss.factory.workstream.persistence.WorkstreamNode
import java.time.Instant

/**
 * Domain model of the versioned workstream registry entry.
 *
 * [slug] and [title] are read-only aliases of [workstreamId] and [name]: they
 * expose the required registry vocabulary without duplicating storage.
 */
data class Workstream(
    val organizationId: String,
    val workstreamId: String,
    val namespaceId: String? = null,
    val name: String,
    val status: WorkstreamStatus,
    val controllerAgentRef: String? = null,
    val allowedWorkflowTypes: List<String> = emptyList(),
    val governancePolicyRef: String? = null,
    val revision: Int = 1,
    val createdAt: Instant = Instant.now(),
    val updatedAt: Instant = Instant.now(),
) {
    /** Stable business identifier of the workstream (alias of [workstreamId]). */
    val slug: String
        get() = workstreamId

    /** Human-readable title of the workstream (alias of [name]). */
    val title: String
        get() = name
}

/** Map a persisted [WorkstreamNode] to its domain [Workstream]. */
fun WorkstreamNode.toDomain(): Workstream =
    Workstream(
        organizationId = organizationId,
        workstreamId = workstreamId,
        namespaceId = namespaceId,
        name = name,
        status = WorkstreamStatus.fromDbValue(status),
        controllerAgentRef = controllerAgentRef,
        allowedWorkflowTypes = allowedWorkflowTypes,
        governancePolicyRef = governancePolicyRef,
        revision = revision,
        createdAt = createdAt,
        updatedAt = updatedAt,
    )

/** Map a domain [Workstream] to its persistable [WorkstreamNode]. */
fun Workstream.toNode(): WorkstreamNode =
    WorkstreamNode(
        id = WorkstreamNode.compositeId(organizationId, workstreamId),
        organizationId = organizationId,
        workstreamId = workstreamId,
        name = name,
        status = status.dbValue,
        namespaceId = namespaceId,
        controllerAgentRef = controllerAgentRef,
        allowedWorkflowTypes = allowedWorkflowTypes,
        governancePolicyRef = governancePolicyRef,
        revision = revision,
        createdAt = createdAt,
        updatedAt = updatedAt,
    )
