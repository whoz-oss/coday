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
 * The former JSONB `payload` column only ever held `{ "status": … }`; the status
 * is promoted to a first-class property here.
 */
@Node("Workstream")
data class WorkstreamNode(
    @Id
    val id: String,
    val organizationId: String,
    val workstreamId: String,
    val name: String,
    val status: String,
    val revision: Int = 1,
    val createdAt: Instant = Instant.now(),
    val updatedAt: Instant = Instant.now(),
) {
    companion object {
        fun compositeId(organizationId: String, workstreamId: String): String = "$organizationId|$workstreamId"
    }
}
