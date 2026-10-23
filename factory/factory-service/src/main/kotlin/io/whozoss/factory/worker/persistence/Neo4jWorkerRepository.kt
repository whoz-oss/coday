package io.whozoss.factory.worker.persistence

import com.fasterxml.jackson.databind.ObjectMapper
import com.fasterxml.jackson.module.kotlin.readValue
import io.whozoss.factory.error.RevisionConflictException
import io.whozoss.factory.persistence.TenantScope
import io.whozoss.factory.worker.domain.Worker
import io.whozoss.factory.worker.domain.WorkerNotFoundException
import io.whozoss.factory.worker.domain.WorkerState
import org.springframework.context.annotation.Primary
import org.springframework.stereotype.Repository
import java.time.Instant

/**
 * Neo4j implementation of [WorkerRepository].
 *
 * Replaces `JdbcWorkerRepository`. A worker is organization-scoped, so every
 * statement is constrained to [TenantScope.organizationId] (the workstream is
 * intentionally ignored). Optimistic locking is enforced by the graph-native
 * compare-and-swap in [SpringDataNeo4jWorkerRepository].
 */
@Repository
@Primary
class Neo4jWorkerRepository(
    private val repository: SpringDataNeo4jWorkerRepository,
    private val objectMapper: ObjectMapper,
) : WorkerRepository {

    override fun insert(scope: TenantScope, worker: Worker): Worker {
        repository.save(worker.toNode(scope))
        return require(scope, worker.workerId)
    }

    override fun findById(scope: TenantScope, workerId: String): Worker? =
        repository
            .findById(WorkerNode.compositeId(scope.organizationId, workerId))
            .orElse(null)
            ?.takeIf { it.organizationId == scope.organizationId }
            ?.toDomain()

    override fun list(scope: TenantScope, statuses: Set<WorkerState>?): List<Worker> =
        repository
            .findAllByOrganization(scope.organizationId)
            .asSequence()
            .map { it.toDomain() }
            .filter { statuses == null || it.status in statuses }
            .toList()

    override fun updateStatus(
        scope: TenantScope,
        workerId: String,
        status: WorkerState,
        expectedRevision: Int,
        updatedAt: Instant,
    ): Worker {
        val updated = repository.casUpdateStatus(
            id = WorkerNode.compositeId(scope.organizationId, workerId),
            expectedRevision = expectedRevision,
            status = status.dbValue,
            updatedAt = updatedAt,
        )
        if (updated == 0L) throw conflictOrNotFound(scope, workerId, expectedRevision)
        return require(scope, workerId)
    }

    override fun heartbeat(
        scope: TenantScope,
        workerId: String,
        heartbeatAt: Instant,
        expectedRevision: Int?,
        updatedAt: Instant,
    ): Worker {
        val updated = repository.updateHeartbeat(
            id = WorkerNode.compositeId(scope.organizationId, workerId),
            heartbeatAt = heartbeatAt,
            expectedRevision = expectedRevision,
            updatedAt = updatedAt,
        )
        if (updated == 0L) throw conflictOrNotFound(scope, workerId, expectedRevision)
        return require(scope, workerId)
    }

    private fun require(scope: TenantScope, workerId: String): Worker =
        findById(scope, workerId)
            ?: throw IllegalStateException("Worker '$workerId' vanished after update")

    private fun conflictOrNotFound(scope: TenantScope, workerId: String, expectedRevision: Int?): RuntimeException {
        val existing = findById(scope, workerId)
        return if (existing != null) {
            RevisionConflictException(
                "Revision conflict for worker '$workerId': expected revision $expectedRevision, " +
                    "found ${existing.revision}",
            )
        } else {
            WorkerNotFoundException("Worker '$workerId' not found")
        }
    }

    private fun Worker.toNode(scope: TenantScope): WorkerNode =
        WorkerNode(
            id = WorkerNode.compositeId(scope.organizationId, workerId),
            organizationId = scope.organizationId,
            workerId = workerId,
            workerType = workerType,
            status = status.dbValue,
            revision = revision,
            lastHeartbeatAt = lastHeartbeatAt,
            protocolVersion = protocolVersion,
            capabilities = objectMapper.writeValueAsString(capabilities),
            payload = payload,
            createdAt = createdAt,
            updatedAt = updatedAt,
        )

    private fun WorkerNode.toDomain(): Worker = Worker(
        organizationId = organizationId,
        workerId = workerId,
        workerType = workerType,
        status = status(),
        revision = revision,
        lastHeartbeatAt = lastHeartbeatAt,
        protocolVersion = protocolVersion,
        capabilities = parseCapabilities(capabilities),
        payload = payload,
        createdAt = createdAt,
        updatedAt = updatedAt,
    )

    private fun parseCapabilities(raw: String?): List<String> {
        if (raw.isNullOrBlank()) return emptyList()
        return try {
            objectMapper.readValue<List<String>>(raw)
        } catch (_: Exception) {
            emptyList()
        }
    }
}
