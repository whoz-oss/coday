package io.whozoss.factory.environment.persistence

import io.whozoss.factory.environment.domain.WorkEnvironment
import io.whozoss.factory.persistence.TenantScope
import java.time.Instant

/**
 * Tenant-scoped persistence port for the `work_environments` aggregate.
 *
 * The lifecycle state maps onto the V6 `work_environments.status` column and the
 * whole descriptor is stored in the JSONB `payload`. Optimistic locking is
 * expressed through [updateState]'s `expectedRevision` compare-and-swap.
 */
interface WorkEnvironmentRepository {

    /** Insert a brand-new environment (revision 1) and return the persisted row. */
    fun insert(scope: TenantScope, environment: WorkEnvironment): WorkEnvironment

    /** Look up one environment by its id within the tenant scope. */
    fun findByEnvironmentId(scope: TenantScope, environmentId: String): WorkEnvironment?

    /** The most recently updated environment of [workflowId], or `null`. */
    fun findLatestByWorkflowId(scope: TenantScope, workflowId: String): WorkEnvironment?

    /** List every environment of the tenant scope. */
    fun list(scope: TenantScope): List<WorkEnvironment>

    /**
     * Compare-and-swap the descriptor: the row is updated only when the stored
     * `revision` equals [expectedRevision]; on success `revision` is incremented
     * by one.
     */
    fun updateState(
        scope: TenantScope,
        environment: WorkEnvironment,
        expectedRevision: Int,
        updatedAt: Instant,
    ): WorkEnvironment
}
