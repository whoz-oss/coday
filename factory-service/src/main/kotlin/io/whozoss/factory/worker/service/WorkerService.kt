package io.whozoss.factory.worker.service

import io.whozoss.factory.persistence.TenantScope
import io.whozoss.factory.worker.domain.InvalidWorkerException
import io.whozoss.factory.worker.domain.InvalidWorkerTransitionException
import io.whozoss.factory.worker.domain.Worker
import io.whozoss.factory.worker.domain.WorkerAlreadyExistsException
import io.whozoss.factory.worker.domain.WorkerNotFoundException
import io.whozoss.factory.worker.domain.WorkerState
import io.whozoss.factory.worker.persistence.WorkerRepository
import org.springframework.stereotype.Service
import org.springframework.transaction.annotation.Transactional
import java.time.Instant

/** Command to register (or upsert) a worker node. */
data class RegisterWorkerCommand(
    val workerId: String,
    val workerType: String,
    val capabilities: List<String> = emptyList(),
    val protocolVersion: String? = null,
    val payload: String = "{}",
)

/**
 * Domain service of the WORKERS aggregate.
 *
 * Owns worker registration (with declared capabilities), liveness heartbeats and
 * the lifecycle state machine ([WorkerState.canTransitionTo]). Port of the
 * worker rules in `factory/src/domain/worker.ts` and
 * `factory/src/adapters/persistence/sql/sql-worker-repository.ts`.
 */
@Service
class WorkerService(
    private val repository: WorkerRepository,
) {

    /** Register a new worker. Declared capabilities are stored as a JSONB array. */
    @Transactional
    fun register(scope: TenantScope, command: RegisterWorkerCommand, now: Instant = Instant.now()): Worker {
        if (command.workerId.isBlank() || command.workerType.isBlank()) {
            throw InvalidWorkerException("'workerId' and 'workerType' are required")
        }
        if (repository.findById(scope, command.workerId) != null) {
            throw WorkerAlreadyExistsException("Worker '${command.workerId}' already exists")
        }
        val worker = Worker(
            organizationId = scope.organizationId,
            workerId = command.workerId,
            workerType = command.workerType,
            status = WorkerState.OFFLINE,
            revision = 1,
            lastHeartbeatAt = now,
            protocolVersion = command.protocolVersion,
            capabilities = command.capabilities,
            payload = command.payload,
            createdAt = now,
            updatedAt = now,
        )
        return repository.insert(scope, worker)
    }

    @Transactional(readOnly = true)
    fun get(scope: TenantScope, workerId: String): Worker =
        repository.findById(scope, workerId)
            ?: throw WorkerNotFoundException("Worker '$workerId' not found")

    @Transactional(readOnly = true)
    fun list(scope: TenantScope, statuses: Set<WorkerState>? = null): List<Worker> =
        repository.list(scope, statuses)

    /** Record a heartbeat: refresh `last_heartbeat_at` and bump `revision`. */
    @Transactional
    fun heartbeat(scope: TenantScope, workerId: String, now: Instant = Instant.now()): Worker =
        repository.heartbeat(scope, workerId, now, expectedRevision = null, updatedAt = now)

    /** Transition a worker through the lifecycle state machine. */
    @Transactional
    fun transition(
        scope: TenantScope,
        workerId: String,
        next: WorkerState,
        now: Instant = Instant.now(),
    ): Worker {
        val current = repository.findById(scope, workerId)
            ?: throw WorkerNotFoundException("Worker '$workerId' not found")
        if (current.status == next) return current
        if (!current.status.canTransitionTo(next)) {
            throw InvalidWorkerTransitionException(
                "Illegal worker transition '${current.status.dbValue}' -> '${next.dbValue}'",
                details = mapOf("from" to current.status.dbValue, "to" to next.dbValue),
            )
        }
        return repository.updateStatus(scope, workerId, next, current.revision, now)
    }
}
