package io.whozoss.factory.worker.persistence

import io.whozoss.factory.persistence.TenantScope
import io.whozoss.factory.worker.domain.Worker
import io.whozoss.factory.worker.domain.WorkerState
import java.time.Instant

/**
 * Persistence port for the worker aggregate.
 *
 * A worker is an organization-scoped node (never workstream-scoped): the
 * [TenantScope.organizationId] is the tenant key, while
 * [TenantScope.workstreamId] is intentionally ignored. Port of
 * `WorkerRepository` in `factory/src/ports/persistence/worker-repository.ts`.
 */
interface WorkerRepository {

    /** Insert a brand-new worker (revision 1) and return the persisted row. */
    fun insert(scope: TenantScope, worker: Worker): Worker

    /** Look up one worker by identity within the organization. */
    fun findById(scope: TenantScope, workerId: String): Worker?

    /** List the workers of the organization, optionally filtered by [statuses]. */
    fun list(scope: TenantScope, statuses: Set<WorkerState>? = null): List<Worker>

    /**
     * Compare-and-swap the lifecycle status: the row is updated only when the
     * stored `revision` equals [expectedRevision]; on success `revision` is
     * incremented by one.
     */
    fun updateStatus(
        scope: TenantScope,
        workerId: String,
        status: WorkerState,
        expectedRevision: Int,
        updatedAt: Instant,
    ): Worker

    /**
     * Record liveness: update `last_heartbeat_at`, bump `revision` and return
     * the persisted worker. Compare-and-swap when [expectedRevision] is supplied.
     */
    fun heartbeat(
        scope: TenantScope,
        workerId: String,
        heartbeatAt: Instant,
        expectedRevision: Int?,
        updatedAt: Instant,
    ): Worker
}
