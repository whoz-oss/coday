package io.whozoss.factory.workstream.persistence

import org.springframework.data.neo4j.core.schema.Id
import org.springframework.data.neo4j.core.schema.Node
import java.time.Instant

/**
 * Spring Data Neo4j projection of the workstream aggregate.
 *
 * Replaces the organization-scoped `workstreams` PostgreSQL row. The node [id]
 * is the composite business key `(organizationId, workstreamId)` encoded as a
 * single string, so a scope-less access is impossible by construction.
 *
 * The node is the versioned workstream registry entry: besides the display
 * [name] and lifecycle [status] it carries the optional governance metadata
 * ([namespaceId], [controllerAgentRef], [allowedWorkflowTypes],
 * [governancePolicyRef]) and the optimistic-locking [revision] with its audit
 * timestamps. New properties are defaulted so pre-existing nodes load without
 * any migration.
 */
@Node("Workstream")
data class WorkstreamNode(
    @Id
    val id: String,
    val organizationId: String,
    val workstreamId: String,
    val name: String,
    val status: String,
    /** Namespace the workstream is bound to, when it declares one. */
    val namespaceId: String? = null,
    /** Optional reference to the agent controlling this workstream. */
    val controllerAgentRef: String? = null,
    /** Workflow types allowed inside this workstream; empty means unrestricted. */
    val allowedWorkflowTypes: List<String> = emptyList(),
    /** Optional reference to the governance policy applied to this workstream. */
    val governancePolicyRef: String? = null,
    val revision: Int = 1,
    val createdAt: Instant = Instant.now(),
    val updatedAt: Instant = Instant.now(),
) {
    companion object {
        fun compositeId(organizationId: String, workstreamId: String): String = "$organizationId|$workstreamId"
    }
}
