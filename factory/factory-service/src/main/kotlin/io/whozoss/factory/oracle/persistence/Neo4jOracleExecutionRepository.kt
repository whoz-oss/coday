package io.whozoss.factory.oracle.persistence

import com.fasterxml.jackson.databind.ObjectMapper
import io.whozoss.factory.error.ResourceNotFoundException
import io.whozoss.factory.error.RevisionConflictException
import io.whozoss.factory.oracle.domain.OracleExecution
import io.whozoss.factory.oracle.domain.OracleExecutionKey
import io.whozoss.factory.oracle.domain.OracleExecutionStatus
import io.whozoss.factory.persistence.TenantScope
import org.springframework.stereotype.Repository
import java.time.Instant

/**
 * Neo4j implementation of [OracleExecutionRepository].
 *
 * Replaces `JdbcOracleExecutionRepository`. Every operation is constrained to
 * the supplied [TenantScope] `(organizationId, workstreamId)` — the node id is
 * the composite business key, so a scope-less access is impossible by
 * construction.
 *
 * Optimistic locking (fencing) is enforced by the graph-native compare-and-swap
 * [SpringDataNeo4jOracleRepository.casUpdateStatus]: the status update only
 * matches a node whose stored `revision` equals `expectedRevision`, and the
 * revision is incremented atomically in the same statement.
 */
@Repository
class Neo4jOracleExecutionRepository(
    private val springDataRepository: SpringDataNeo4jOracleRepository,
    private val objectMapper: ObjectMapper,
) : OracleExecutionRepository {

    override fun save(scope: TenantScope, execution: OracleExecution): OracleExecution {
        val idempotencyKey = extractIdempotencyKey(execution.payload)
        springDataRepository.save(OracleExecutionNode.fromDomain(execution, idempotencyKey))
        return findByKey(scope, execution.key)
            ?: throw IllegalStateException("Oracle execution '${execution.executionId}' vanished after insert")
    }

    override fun updateStatus(
        scope: TenantScope,
        namespaceId: String,
        workflowId: String,
        executionId: String,
        status: OracleExecutionStatus,
        expectedRevision: Int,
        artifactId: String?,
        evidenceId: String?,
        payload: String?,
    ): OracleExecution {
        val key = OracleExecutionKey(namespaceId, workflowId, executionId)
        val id = OracleExecutionNode.compositeId(scope.organizationId, scope.workstreamId, key)
        val updated = springDataRepository.casUpdateStatus(
            id = id,
            expectedRevision = expectedRevision,
            status = status.dbValue,
            evidenceId = evidenceId,
            artifactId = artifactId,
            payload = payload,
            updatedAt = Instant.now(),
        )
        if (updated == 0L) {
            val existing = springDataRepository.findById(id).orElse(null)
            if (existing != null) {
                throw RevisionConflictException(
                    "Revision conflict for oracle execution '$executionId': expected revision $expectedRevision, " +
                        "found ${existing.revision}",
                )
            }
            throw ResourceNotFoundException("Oracle execution '$executionId' not found")
        }
        return findByKey(scope, key)
            ?: throw IllegalStateException("Oracle execution '$executionId' vanished after update")
    }

    override fun findById(scope: TenantScope, id: OracleExecutionKey): OracleExecution? = findByKey(scope, id)

    override fun findByKey(scope: TenantScope, key: OracleExecutionKey): OracleExecution? =
        springDataRepository.findById(OracleExecutionNode.compositeId(scope.organizationId, scope.workstreamId, key))
            .orElse(null)
            ?.takeIf { it.organizationId == scope.organizationId && it.workstreamId == scope.workstreamId }
            ?.toDomain()

    override fun findByIdempotencyKey(scope: TenantScope, idempotencyKey: String): OracleExecution? =
        springDataRepository
            .findByIdempotencyKey(scope.organizationId, scope.workstreamId, idempotencyKey)
            ?.toDomain()

    override fun deleteById(scope: TenantScope, id: OracleExecutionKey): Boolean {
        val existing = findByKey(scope, id) ?: return false
        springDataRepository.deleteById(
            OracleExecutionNode.compositeId(scope.organizationId, scope.workstreamId, existing.key),
        )
        return true
    }

    /**
     * The idempotency key lives inside the JSON `payload`
     * (`payload->>'idempotencyKey'` in the former SQL adapter). It is also
     * denormalised onto the node so replay lookups are an indexed property
     * match. Malformed payloads simply yield no key.
     */
    private fun extractIdempotencyKey(payload: String): String? =
        runCatching {
            @Suppress("UNCHECKED_CAST")
            val parsed = objectMapper.readValue(payload, Map::class.java) as Map<String, Any?>
            parsed["idempotencyKey"] as? String
        }.getOrNull()
}
