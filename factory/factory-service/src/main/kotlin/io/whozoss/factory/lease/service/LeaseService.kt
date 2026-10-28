package io.whozoss.factory.lease.service

import io.whozoss.factory.lease.domain.AcquireLeaseResult
import io.whozoss.factory.lease.domain.LeaseExpiryReasons
import io.whozoss.factory.lease.domain.NoEligibleWorkUnitException
import io.whozoss.factory.lease.domain.WorkUnitLease
import io.whozoss.factory.lease.persistence.LeaseRepository
import io.whozoss.factory.persistence.TenantScope
import io.whozoss.factory.workunit.domain.WorkUnitState
import org.springframework.stereotype.Service
import org.springframework.transaction.annotation.Transactional
import java.time.Instant

/**
 * Transactional application service of the lease protocol.
 *
 * Every protocol mutation runs inside a single transaction so the lease row and
 * the work-unit row always flip together. The actual SQL (including the
 * `FOR UPDATE SKIP LOCKED` claim and the `work_unit_lease_fencing_seq` draw)
 * lives in [LeaseRepository].
 */
@Service
class LeaseService(
    private val repository: LeaseRepository,
) {

    /**
     * Claim the highest-priority eligible work unit, or return `null` when none
     * is eligible. The whole claim (scan, token draw, insert, unit advance) is
     * atomic.
     */
    @Transactional
    fun acquire(
        scope: TenantScope,
        workerId: String,
        ttlMs: Long,
        environmentId: String? = null,
        now: Instant = Instant.now(),
    ): AcquireLeaseResult? = repository.acquire(scope, workerId, ttlMs, environmentId, now)

    /**
     * Same as [acquire] but fails with `NO_ELIGIBLE_WORK_UNIT` (404) instead of
     * returning `null`.
     */
    @Transactional
    fun acquireRequired(
        scope: TenantScope,
        workerId: String,
        ttlMs: Long,
        environmentId: String? = null,
        now: Instant = Instant.now(),
    ): AcquireLeaseResult =
        acquire(scope, workerId, ttlMs, environmentId, now)
            ?: throw NoEligibleWorkUnitException(
                "No eligible work unit for worker '$workerId'",
                details = mapOf(
                    "organizationId" to scope.organizationId,
                    "workstreamId" to scope.workstreamId,
                ),
            )

    /** Heartbeat: validate state + fencing token, then extend the deadline. */
    @Transactional
    fun renew(
        scope: TenantScope,
        workUnitId: String,
        leaseId: String,
        fencingToken: Long,
        ttlMs: Long,
        now: Instant = Instant.now(),
    ): WorkUnitLease = repository.renew(scope, workUnitId, leaseId, fencingToken, ttlMs, now)

    /** Release an active lease and transition its work unit to [resultStatus]. */
    @Transactional
    fun release(
        scope: TenantScope,
        workUnitId: String,
        leaseId: String,
        fencingToken: Long? = null,
        resultStatus: WorkUnitState = WorkUnitState.COMPLETED,
        now: Instant = Instant.now(),
    ): WorkUnitLease = repository.release(scope, workUnitId, leaseId, fencingToken, resultStatus, now)

    /**
     * Reap every active lease past its deadline, defaulting the expiry reason to
     * `heartbeat_timeout`.
     */
    @Transactional
    fun expire(
        scope: TenantScope,
        expiryReason: String = LeaseExpiryReasons.HEARTBEAT_TIMEOUT,
        now: Instant = Instant.now(),
    ): List<WorkUnitLease> = repository.expire(scope, expiryReason, now)

    @Transactional(readOnly = true)
    fun findByLeaseId(scope: TenantScope, workUnitId: String, leaseId: String): WorkUnitLease? =
        repository.findByLeaseId(scope, workUnitId, leaseId)

    @Transactional(readOnly = true)
    fun findActiveLeaseByWorkUnit(scope: TenantScope, workUnitId: String): WorkUnitLease? =
        repository.findActiveLeaseByWorkUnit(scope, workUnitId)
}
