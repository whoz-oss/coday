package io.whozoss.factory.delivery.persistence

import org.springframework.data.neo4j.repository.Neo4jRepository
import org.springframework.data.neo4j.repository.query.Query

/**
 * Spring Data Neo4j repository for [DeliveryNode] (the durable snapshot).
 */
interface SpringDataNeo4jDeliveryRepository : Neo4jRepository<DeliveryNode, String>

/**
 * Spring Data Neo4j repository for [DeliveryRecordNode] (the append-only
 * journal).
 *
 * [maxRecordSequence] implements the graph-native sequence generation —
 * `MAX(recordSequence) + 1` — replacing the former PostgreSQL
 * `COALESCE(MAX(record_sequence), 0)` statement.
 */
interface SpringDataNeo4jDeliveryRecordRepository : Neo4jRepository<DeliveryRecordNode, String> {

    @Query(
        """
        MATCH (r:DeliveryRecord)
        WHERE r.organizationId = ${'$'}organizationId
          AND r.workstreamId = ${'$'}workstreamId
          AND r.namespaceId = ${'$'}namespaceId
          AND r.deliveryId = ${'$'}deliveryId
        RETURN coalesce(max(r.recordSequence), 0) AS maxSequence
        """,
    )
    fun maxRecordSequence(
        organizationId: String,
        workstreamId: String,
        namespaceId: String,
        deliveryId: String,
    ): Long

    @Query(
        """
        MATCH (r:DeliveryRecord)
        WHERE r.organizationId = ${'$'}organizationId
          AND r.workstreamId = ${'$'}workstreamId
          AND r.namespaceId = ${'$'}namespaceId
          AND r.deliveryId = ${'$'}deliveryId
        RETURN r
        ORDER BY r.recordSequence ASC
        """,
    )
    fun findByScopeAndDelivery(
        organizationId: String,
        workstreamId: String,
        namespaceId: String,
        deliveryId: String,
    ): List<DeliveryRecordNode>
}
