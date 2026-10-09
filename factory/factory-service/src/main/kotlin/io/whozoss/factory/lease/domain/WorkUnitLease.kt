package io.whozoss.factory.lease.domain

import java.time.Instant

/**
 * A durable work-unit lease as persisted in `work_unit_leases`.
 *
 * Port of the `WorkUnitLease` interface in `factory/src/domain/lease/lease.ts`.
 * Nullable timestamps mirror the nullable V7 columns (rows created before the
 * protocol predate them).
 */
data class WorkUnitLease(
    val organizationId: String,
    val workstreamId: String,
    val workUnitId: String,
    val leaseId: String,
    val workerId: String,
    val environmentId: String? = null,
    val status: LeaseStatus = LeaseStatus.ACTIVE,
    /** Monotone fencing token drawn from `work_unit_lease_fencing_seq`. */
    val fencingToken: Long,
    val acquiredAt: Instant? = null,
    val leaseExpiresAt: Instant? = null,
    val heartbeatAt: Instant? = null,
    val releasedAt: Instant? = null,
    val expiryReason: String? = null,
    val createdAt: Instant? = null,
) {
    /** True when the lease is `active` and still within its deadline at [now]. */
    fun isActive(now: Instant = Instant.now()): Boolean = status == LeaseStatus.ACTIVE && !isExpiredByTime(now)

    /** True when an `active` lease is past its deadline relative to [now]. */
    fun isExpiredByTime(now: Instant): Boolean {
        if (status != LeaseStatus.ACTIVE) return false
        val deadline = leaseExpiresAt ?: return false
        return !deadline.isAfter(now)
    }
}

/** Result of a successful [io.whozoss.factory.lease.service.LeaseService.acquire]. */
data class AcquireLeaseResult(
    val lease: WorkUnitLease,
    val workUnitId: String,
)
