package io.whozoss.factory.environment.persistence

import io.whozoss.factory.environment.domain.WorkEnvironment
import io.whozoss.factory.environment.domain.WorkEnvironmentState
import org.springframework.data.neo4j.core.schema.Id
import org.springframework.data.neo4j.core.schema.Node
import java.time.Instant

/**
 * Spring Data Neo4j projection of the [WorkEnvironment] aggregate.
 *
 * Replaces the `work_environments` PostgreSQL row. The node [id] is the
 * composite business key `(organizationId, workstreamId, environmentId)`
 * encoded as a single string. The full descriptor is kept verbatim in the JSON
 * [payload] (the former JSONB column); [workflowId], [status] and [revision]
 * are denormalised onto the node so workflow-scoped lookups, ordering and the
 * optimistic-locking compare-and-swap stay indexed property operations.
 */
@Node("WorkEnvironment")
data class WorkEnvironmentNode(
    @Id
    val id: String,
    val organizationId: String,
    val workstreamId: String,
    val environmentId: String,
    val workflowId: String,
    val status: String,
    val revision: Int,
    val payload: String,
    val createdAt: Instant,
    val updatedAt: Instant,
) {
    fun status(): WorkEnvironmentState = WorkEnvironmentState.fromDbValue(status)

    companion object {
        fun compositeId(organizationId: String, workstreamId: String, environmentId: String): String =
            "$organizationId|$workstreamId|$environmentId"
    }
}
