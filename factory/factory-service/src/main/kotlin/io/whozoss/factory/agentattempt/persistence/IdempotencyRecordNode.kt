package io.whozoss.factory.agentattempt.persistence

import org.springframework.data.neo4j.core.schema.Id
import org.springframework.data.neo4j.core.schema.Node
import java.time.Instant

/**
 * Spring Data Neo4j projection of the V4 `idempotency_records` dedupe/response
 * cache.
 *
 * The node [id] is the tenant-scoped key `(organizationId, idempotencyKey)`
 * encoded as a single string, so the former primary key uniqueness is the graph
 * uniqueness constraint. [requestHash] fingerprints the request so a replay with
 * a divergent body is rejected while an identical replay is answered from
 * [responsePayload].
 */
@Node("IdempotencyRecord")
data class IdempotencyRecordNode(
    @Id
    val id: String,
    val organizationId: String,
    val idempotencyKey: String,
    val workstreamId: String,
    val requestHash: String,
    val resourceRef: String,
    val status: String,
    val responsePayload: String,
    val createdAt: Instant = Instant.now(),
) {
    companion object {
        fun compositeId(organizationId: String, idempotencyKey: String): String = "$organizationId|$idempotencyKey"
    }
}
