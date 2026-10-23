package io.whozoss.factory.workunit.persistence

import io.whozoss.factory.persistence.TenantScope
import io.whozoss.factory.workunit.domain.WorkUnit
import io.whozoss.factory.workunit.domain.WorkUnitState

/**
 * Tenant-scoped persistence port for the `work_units` aggregate.
 *
 * Every operation takes the [TenantScope] it must run in. Optimistic locking is
 * expressed through [updateStatus]'s `expectedRevision` compare-and-swap.
 */
interface WorkUnitRepository {

    /** Insert a brand-new work unit (revision 1) and return the persisted row. */
    fun insert(scope: TenantScope, workUnit: WorkUnit): WorkUnit

    /** Look up one work unit by its identity within the tenant scope. */
    fun findById(scope: TenantScope, workUnitId: String): WorkUnit?

    /** List the work units of the scope, optionally filtered by [statuses]. */
    fun list(scope: TenantScope, statuses: Set<WorkUnitState>? = null): List<WorkUnit>

    /**
     * Compare-and-swap the lifecycle status: the row is updated only when the
     * stored `revision` equals [expectedRevision]; on success `revision` is
     * incremented by one.
     */
    fun updateStatus(
        scope: TenantScope,
        workUnitId: String,
        status: WorkUnitState,
        expectedRevision: Int,
        updatedAt: java.time.Instant,
    ): WorkUnit
}
