package io.whozoss.factory.lease.persistence

import io.whozoss.factory.lease.domain.AcquireLeaseResult
import io.whozoss.factory.lease.domain.InvalidLeaseStateException
import io.whozoss.factory.lease.domain.LeaseExpiredException
import io.whozoss.factory.lease.domain.LeaseFencedException
import io.whozoss.factory.lease.domain.LeaseStatus
import io.whozoss.factory.lease.domain.WorkUnitLease
import io.whozoss.factory.persistence.TenantScope
import io.whozoss.factory.workunit.domain.WorkUnitNotFoundException
import io.whozoss.factory.workunit.domain.WorkUnitState
import io.whozoss.factory.workunit.persistence.SpringDataNeo4jWorkUnitRepository
import io.whozoss.factory.workunit.persistence.WorkUnitNode
import org.springframework.context.annotation.Primary
import org.springframework.stereotype.Repository
import org.springframework.transaction.PlatformTransactionManager
import org.springframework.transaction.TransactionDefinition
import org.springframework.transaction.support.TransactionTemplate
import java.time.Instant
import java.util.UUID
import java.util.concurrent.locks.ReentrantLock
import kotlin.concurrent.withLock

/**
 * Neo4j implementation of the lease protocol.
 *
 * Replaces `JdbcLeaseRepository`. The fencing counter is derived from the
 * high-water mark of the `:WorkUnitLease.fencingToken` property; acquisitions
 * are serialised by a process-local lock, which is exactly the guarantee the
 * former `SELECT ... FOR UPDATE SKIP LOCKED` + `nextval(...)` pair provided for
 * the single-process embedded engine.
 *
 * ## Concurrency
 * [acquire] acquires [claimLock] *before* starting its own
 * `REQUIRES_NEW` transaction and releases it only once that transaction has
 * committed. A competing acquisition therefore never observes a snapshot taken
 * before the winner's commit: by the time it can look at the reclaimable work
 * units, the winner's `running` transition is durable.
 */
@Repository
@Primary
class Neo4jLeaseRepository(
    private val leases: SpringDataNeo4jLeaseRepository,
    private val workUnits: SpringDataNeo4jWorkUnitRepository,
    transactionManager: PlatformTransactionManager,
) : LeaseRepository {

    private val claimTransaction = TransactionTemplate(transactionManager).apply {
        propagationBehavior = TransactionDefinition.PROPAGATION_REQUIRES_NEW
    }

    override fun acquire(
        scope: TenantScope,
        workerId: String,
        ttlMs: Long,
        environmentId: String?,
        now: Instant,
    ): AcquireLeaseResult? {
        requireTtl(ttlMs)
        return claimLock.withLock {
            claimTransaction.execute {
                doAcquire(scope, workerId, ttlMs, environmentId, now)
            }
        }
    }

    override fun renew(
        scope: TenantScope,
        workUnitId: String,
        leaseId: String,
        fencingToken: Long,
        ttlMs: Long,
        now: Instant,
    ): WorkUnitLease {
        requireTtl(ttlMs)
        val node = requireLease(scope, workUnitId, leaseId)
        val lease = node.toDomain()
        if (lease.status != LeaseStatus.ACTIVE) {
            throw InvalidLeaseStateException(
                "Cannot renew lease in state '${lease.status.dbValue}'",
                details = mapOf("leaseId" to leaseId, "status" to lease.status.dbValue),
            )
        }
        if (lease.isExpiredByTime(now)) {
            throw LeaseExpiredException(
                "Lease '$leaseId' expired at ${lease.leaseExpiresAt}",
                details = mapOf("leaseId" to leaseId, "leaseExpiresAt" to lease.leaseExpiresAt.toString()),
            )
        }
        assertFencingToken(lease, fencingToken)
        val saved = node.copy(leaseExpiresAt = now.plusMillis(ttlMs), heartbeatAt = now)
        leases.save(saved)
        return saved.toDomain()
    }

    override fun release(
        scope: TenantScope,
        workUnitId: String,
        leaseId: String,
        fencingToken: Long?,
        resultStatus: WorkUnitState,
        now: Instant,
    ): WorkUnitLease {
        val node = requireLease(scope, workUnitId, leaseId)
        if (fencingToken != null) assertFencingToken(node.toDomain(), fencingToken)
        if (node.status() != LeaseStatus.ACTIVE) {
            throw InvalidLeaseStateException(
                "Cannot release lease in state '${node.status().dbValue}'",
                details = mapOf("leaseId" to leaseId, "status" to node.status().dbValue),
            )
        }
        val saved = node.copy(status = LeaseStatus.RELEASED.dbValue, releasedAt = now)
        leases.save(saved)
        advanceWorkUnit(scope, workUnitId, resultStatus, now)
        return saved.toDomain()
    }

    override fun expire(
        scope: TenantScope,
        expiryReason: String,
        now: Instant,
    ): List<WorkUnitLease> =
        leases.findAllActiveByScope(scope.organizationId, scope.workstreamId)
            .filter { it.leaseExpiresAt != null && !it.leaseExpiresAt.isAfter(now) }
            .map { node ->
                val saved = node.copy(
                    status = LeaseStatus.EXPIRED.dbValue,
                    releasedAt = now,
                    expiryReason = expiryReason,
                )
                leases.save(saved)
                // `attemptCount` is deliberately NOT bumped here: it was already
                // incremented when the lease was acquired, so the sweep only
                // re-queues the unit (no double counting of one execution attempt).
                requeueWorkUnit(scope, node.workUnitId, now)
                saved.toDomain()
            }

    override fun findByLeaseId(
        scope: TenantScope,
        workUnitId: String,
        leaseId: String,
    ): WorkUnitLease? = findLease(scope, workUnitId, leaseId)?.toDomain()

    override fun findActiveLeaseByWorkUnit(scope: TenantScope, workUnitId: String): WorkUnitLease? =
        leases.findActiveByWorkUnit(scope.organizationId, scope.workstreamId, workUnitId)?.toDomain()

    private fun doAcquire(
        scope: TenantScope,
        workerId: String,
        ttlMs: Long,
        environmentId: String?,
        now: Instant,
    ): AcquireLeaseResult? {
        val candidate = workUnits
            .findReclaimable(scope.organizationId, scope.workstreamId)
            .firstOrNull { it.notBefore == null || !it.notBefore.isAfter(now) }
            ?: return null

        val fencingToken = leases.maxFencingToken() + 1
        val leaseId = "lease_${UUID.randomUUID()}"
        val lease = LeaseNode(
            id = LeaseNode.compositeId(scope.organizationId, scope.workstreamId, candidate.workUnitId, leaseId),
            organizationId = scope.organizationId,
            workstreamId = scope.workstreamId,
            workUnitId = candidate.workUnitId,
            leaseId = leaseId,
            workerId = workerId,
            environmentId = environmentId,
            status = LeaseStatus.ACTIVE.dbValue,
            fencingToken = fencingToken,
            acquiredAt = now,
            leaseExpiresAt = now.plusMillis(ttlMs),
            heartbeatAt = now,
            createdAt = now,
        )
        leases.save(lease)
        workUnits.save(
            candidate.copy(
                status = WorkUnitState.RUNNING.dbValue,
                attemptCount = candidate.attemptCount + 1,
                revision = candidate.revision + 1,
                updatedAt = now,
            ),
        )
        return AcquireLeaseResult(lease = lease.toDomain(), workUnitId = candidate.workUnitId)
    }

    private fun requireLease(scope: TenantScope, workUnitId: String, leaseId: String): LeaseNode =
        findLease(scope, workUnitId, leaseId)
            ?: throw WorkUnitNotFoundException("Lease '$leaseId' for work unit '$workUnitId' not found")

    private fun findLease(scope: TenantScope, workUnitId: String, leaseId: String): LeaseNode? =
        leases
            .findById(LeaseNode.compositeId(scope.organizationId, scope.workstreamId, workUnitId, leaseId))
            .orElse(null)
            ?.takeIf { it.organizationId == scope.organizationId && it.workstreamId == scope.workstreamId }

    private fun advanceWorkUnit(scope: TenantScope, workUnitId: String, status: WorkUnitState, now: Instant) {
        val node = workUnit(scope, workUnitId)
            ?: throw WorkUnitNotFoundException("Work unit '$workUnitId' not found")
        workUnits.save(node.copy(status = status.dbValue, revision = node.revision + 1, updatedAt = now))
    }

    private fun requeueWorkUnit(scope: TenantScope, workUnitId: String, now: Instant) {
        val node = workUnit(scope, workUnitId) ?: return
        workUnits.save(node.copy(status = WorkUnitState.CREATED.dbValue, revision = node.revision + 1, updatedAt = now))
    }

    private fun workUnit(scope: TenantScope, workUnitId: String): WorkUnitNode? =
        workUnits
            .findById(WorkUnitNode.compositeId(scope.organizationId, scope.workstreamId, workUnitId))
            .orElse(null)

    private fun assertFencingToken(lease: WorkUnitLease, fencingToken: Long) {
        if (lease.fencingToken != fencingToken) {
            throw LeaseFencedException(
                "Fencing token $fencingToken is stale for lease '${lease.leaseId}' (current ${lease.fencingToken})",
                details = mapOf(
                    "currentFencingToken" to lease.fencingToken,
                    "incomingFencingToken" to fencingToken,
                ),
            )
        }
    }

    private fun requireTtl(ttlMs: Long) {
        require(ttlMs > 0) { "INVALID_LEASE_TTL" }
    }

    private companion object {
        /**
         * Serialises every acquisition of the embedded (single-process) engine,
         * mirroring the former `FOR UPDATE SKIP LOCKED` non-blocking claim.
         */
        private val claimLock = ReentrantLock()
    }
}
