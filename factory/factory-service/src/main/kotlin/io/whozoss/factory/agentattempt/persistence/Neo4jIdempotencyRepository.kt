package io.whozoss.factory.agentattempt.persistence

import io.whozoss.factory.persistence.TenantScope
import org.springframework.stereotype.Repository
import org.springframework.transaction.annotation.Transactional
import java.time.Instant

/**
 * Neo4j implementation of [IdempotencyRepository].
 *
 * Replaces `JdbcIdempotencyRepository`. The tenant-scoped composite node id
 * `(organizationId, idempotencyKey)` is the uniqueness guarantee, so a second
 * submission of the same key by the same tenant is answered from the first
 * record. The former `ON CONFLICT DO NOTHING` is expressed as an existence check
 * before the save (the embedded engine is single-writer).
 */
@Repository
class Neo4jIdempotencyRepository(
    private val records: SpringDataNeo4jIdempotencyRepository,
) : IdempotencyRepository {

    override fun find(scope: TenantScope, idempotencyKey: String): IdempotencyRecord? =
        records
            .findById(IdempotencyRecordNode.compositeId(scope.organizationId, idempotencyKey))
            .orElse(null)
            ?.takeIf { it.organizationId == scope.organizationId }
            ?.let { IdempotencyRecord(requestHash = it.requestHash, responsePayload = it.responsePayload, status = it.status) }

    @Transactional
    override fun save(scope: TenantScope, idempotencyKey: String, requestHash: String, responsePayload: String) {
        val id = IdempotencyRecordNode.compositeId(scope.organizationId, idempotencyKey)
        if (records.existsById(id)) return
        records.save(
            IdempotencyRecordNode(
                id = id,
                organizationId = scope.organizationId,
                idempotencyKey = idempotencyKey,
                workstreamId = scope.workstreamId,
                requestHash = requestHash,
                resourceRef = RESOURCE_REF,
                status = COMPLETED,
                responsePayload = responsePayload,
                createdAt = Instant.now(),
            ),
        )
    }

    private companion object {
        const val RESOURCE_REF = "agent-step-result"
        const val COMPLETED = "completed"
    }
}
