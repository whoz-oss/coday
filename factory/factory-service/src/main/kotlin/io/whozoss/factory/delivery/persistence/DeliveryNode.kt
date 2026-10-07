package io.whozoss.factory.delivery.persistence

import org.springframework.data.neo4j.core.schema.Id
import org.springframework.data.neo4j.core.schema.Node
import java.time.Instant

/**
 * Spring Data Neo4j projection of the durable delivery snapshot.
 *
 * Replaces the `deliveries` PostgreSQL row. The node [id] is the composite
 * business key `(organizationId, workstreamId, namespaceId, deliveryId)`
 * encoded as a single string. The verbatim snapshot JSON stays in [payload]
 * (the former JSONB column); [revision] and [stage] are denormalised for
 * indexing but the payload remains authoritative on read.
 */
@Node("Delivery")
data class DeliveryNode(
    @Id
    val id: String,
    val organizationId: String,
    val workstreamId: String,
    val namespaceId: String,
    val deliveryId: String,
    val revision: Int,
    val stage: String? = null,
    val payload: String,
    val createdAt: Instant,
    val updatedAt: Instant,
) {
    companion object {
        fun compositeId(
            organizationId: String,
            workstreamId: String,
            namespaceId: String,
            deliveryId: String,
        ): String = "$organizationId|$workstreamId|$namespaceId|$deliveryId"
    }
}
