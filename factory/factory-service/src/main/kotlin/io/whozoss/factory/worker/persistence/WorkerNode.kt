package io.whozoss.factory.worker.persistence

import io.whozoss.factory.worker.domain.Worker
import io.whozoss.factory.worker.domain.WorkerState
import org.springframework.data.neo4j.core.schema.Id
import org.springframework.data.neo4j.core.schema.Node
import java.time.Instant

/**
 * Spring Data Neo4j projection of the [Worker] aggregate.
 *
 * Replaces the organization-scoped `workers` PostgreSQL row. The node [id] is
 * the composite business key `(organizationId, workerId)` encoded as a single
 * string; a worker is never workstream-scoped, so [WorkerRepository] ignores
 * [io.whozoss.factory.persistence.TenantScope.workstreamId].
 *
 * `capabilities` and `payload` stay as their raw JSON text (the former JSONB
 * columns) so the persistence layer imposes no schema on them.
 */
@Node("Worker")
data class WorkerNode(
    @Id
    val id: String,
    val organizationId: String,
    val workerId: String,
    val workerType: String,
    val status: String,
    val revision: Int,
    val lastHeartbeatAt: Instant? = null,
    val protocolVersion: String? = null,
    val capabilities: String = "[]",
    val payload: String = "{}",
    val createdAt: Instant = Instant.now(),
    val updatedAt: Instant = Instant.now(),
) {
    fun status(): WorkerState = WorkerState.fromDbValue(status)

    companion object {
        fun compositeId(organizationId: String, workerId: String): String = "$organizationId|$workerId"
    }
}
