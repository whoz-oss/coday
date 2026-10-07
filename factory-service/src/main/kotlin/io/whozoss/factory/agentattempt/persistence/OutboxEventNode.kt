package io.whozoss.factory.agentattempt.persistence

import org.springframework.data.neo4j.core.schema.Id
import org.springframework.data.neo4j.core.schema.Node
import java.time.Instant

/**
 * Spring Data Neo4j projection of a transactional-outbox event.
 *
 * Replaces the V4 `outbox_events` PostgreSQL row (previously written by the
 * JDBC result adapter and drained by `OutboxDrainService`). The node [id] is the
 * composite business key `(organizationId, eventId)` encoded as a single string;
 * the bare `eventId` is kept as a property so the drain queries stay readable.
 *
 * Events are enqueued `pending` and drained oldest-first; a successful dispatch
 * sets [status] `dispatched` and stamps [dispatchedAt], a failing handler sets
 * `failed` and increments [attempts].
 */
@Node("OutboxEvent")
data class OutboxEventNode(
    @Id
    val id: String,
    val organizationId: String,
    val eventId: String,
    val workstreamId: String,
    val eventType: String,
    val payload: String = "{}",
    val status: String,
    val attempts: Int = 0,
    val createdAt: Instant = Instant.now(),
    val dispatchedAt: Instant? = null,
) {
    companion object {
        fun compositeId(organizationId: String, eventId: String): String = "$organizationId|$eventId"
    }
}
