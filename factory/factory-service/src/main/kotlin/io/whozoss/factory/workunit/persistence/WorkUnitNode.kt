package io.whozoss.factory.workunit.persistence

import io.whozoss.factory.workunit.domain.WorkUnit
import io.whozoss.factory.workunit.domain.WorkUnitState
import org.springframework.data.neo4j.core.schema.Id
import org.springframework.data.neo4j.core.schema.Node
import java.time.Instant

/**
 * Spring Data Neo4j projection of the [WorkUnit] aggregate.
 *
 * Replaces the tenant-scoped `work_units` PostgreSQL row. The node [id] is the
 * composite business key `(organizationId, workstreamId, workUnitId)` encoded as
 * a single string, so a scope-less access is impossible by construction.
 *
 * `payload` stays as its raw JSON text (the former JSONB column).
 */
@Node("WorkUnit")
data class WorkUnitNode(
    @Id
    val id: String,
    val organizationId: String,
    val workstreamId: String,
    val workUnitId: String,
    val unitType: String,
    val status: String,
    val revision: Int,
    val priority: Int = 0,
    val notBefore: Instant? = null,
    val attemptCount: Int = 0,
    val payload: String = "{}",
    val createdAt: Instant = Instant.now(),
    val updatedAt: Instant = Instant.now(),
) {
    fun status(): WorkUnitState = WorkUnitState.fromDbValue(status)

    companion object {
        fun compositeId(organizationId: String, workstreamId: String, workUnitId: String): String =
            "$organizationId|$workstreamId|$workUnitId"
    }
}

/** Maps a [WorkUnit] domain aggregate to its graph projection. */
fun WorkUnit.toNode(): WorkUnitNode = WorkUnitNode(
    id = WorkUnitNode.compositeId(organizationId, workstreamId, workUnitId),
    organizationId = organizationId,
    workstreamId = workstreamId,
    workUnitId = workUnitId,
    unitType = unitType,
    status = status.dbValue,
    revision = revision,
    priority = priority,
    notBefore = notBefore,
    attemptCount = attemptCount,
    payload = payload,
    createdAt = createdAt,
    updatedAt = updatedAt,
)

/** Maps a graph node back to the [WorkUnit] domain aggregate. */
fun WorkUnitNode.toDomain(): WorkUnit = WorkUnit(
    organizationId = organizationId,
    workstreamId = workstreamId,
    workUnitId = workUnitId,
    unitType = unitType,
    status = status(),
    revision = revision,
    priority = priority,
    notBefore = notBefore,
    attemptCount = attemptCount,
    payload = payload,
    createdAt = createdAt,
    updatedAt = updatedAt,
)
