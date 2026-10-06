package io.whozoss.factory.lease.persistence

import io.whozoss.factory.lease.domain.AcquireLeaseResult
import io.whozoss.factory.lease.domain.WorkUnitLease
import io.whozoss.factory.persistence.TenantScope
import io.whozoss.factory.workunit.domain.WorkUnitState
import java.time.Instant

/**
 * Tenant-scoped persistence port for the work-unit lease protocol.
 *
 * Port of `LeaseRepository` in
 * `factory/src/ports/persistence/lease-repository.ts`. Every mutation runs
 * inside the caller's transaction (see
 * [io.whozoss.factory.lease.service.LeaseService]) so the lease row and the
 * work-unit row always flip together.
 */
interface LeaseRepository {

    /**
     * Claim the highest-priority eligible work unit with
     * `SELECT ... FOR UPDATE SKIP LOCKED`, draw the next monotone fencing token
     * from `work_unit_lease_fencing_seq`, insert the lease and advance the work
     * unit to `running`. Returns `null` when no unit is eligible.
     */
    fun acquire(
        scope: TenantScope,
        workerId: String,
        ttlMs: Long,
        environmentId: String? = null,
        now: Instant = Instant.now(),
    ): AcquireLeaseResult?

    /** Heartbeat: validate state + fencing token, then extend the deadline. */
    fun renew(
        scope: TenantScope,
        workUnitId: String,
        leaseId: String,
        fencingToken: Long,
        ttlMs: Long,
        now: Instant = Instant.now(),
    ): WorkUnitLease

    /**
     * Release an active lease: validate the (optional) fencing token, mark the
     * lease `released` and transition the work unit to [resultStatus].
     */
    fun release(
        scope: TenantScope,
        workUnitId: String,
        leaseId: String,
        fencingToken: Long?,
        resultStatus: WorkUnitState,
        now: Instant = Instant.now(),
    ): WorkUnitLease

    /**
     * Sweep every active lease past its deadline: mark it `expired` with
     * [expiryReason] and re-queue its work unit as `created`.
     */
    fun expire(
        scope: TenantScope,
        expiryReason: String,
        now: Instant = Instant.now(),
    ): List<WorkUnitLease>

    /** Look up one lease by its identity. */
    fun findByLeaseId(
        scope: TenantScope,
        workUnitId: String,
        leaseId: String,
    ): WorkUnitLease?

    /** The most recent `active` lease of a work unit, or `null`. */
    fun findActiveLeaseByWorkUnit(scope: TenantScope, workUnitId: String): WorkUnitLease?
}
