package io.whozoss.factory.lease

import io.whozoss.factory.DomainIntegrationTest
import io.whozoss.factory.lease.domain.LeaseExpiryReasons
import io.whozoss.factory.lease.domain.LeaseExpiredException
import io.whozoss.factory.lease.domain.LeaseFencedException
import io.whozoss.factory.lease.domain.LeaseStatus
import io.whozoss.factory.lease.domain.NoEligibleWorkUnitException
import io.whozoss.factory.lease.service.LeaseService
import io.whozoss.factory.workunit.domain.WorkUnitState
import io.whozoss.factory.workunit.service.CreateWorkUnitCommand
import io.whozoss.factory.workunit.service.WorkUnitService
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Assertions.assertThrows
import org.junit.jupiter.api.Test
import org.springframework.beans.factory.annotation.Autowired

/**
 * Fencing, expiry and re-queue integration tests of the lease protocol.
 *
 * Ports the exact machine-code contract of
 * `factory/src/domain/lease/lease.ts`: a stale fencing token yields
 * `LEASE_FENCED`, a lease past its deadline yields `LEASE_EXPIRED` with the
 * `heartbeat_timeout` reason, and an empty eligible scan yields
 * `NO_ELIGIBLE_WORK_UNIT`.
 */
class LeaseFencingAndExpiryTest : DomainIntegrationTest() {

    @Autowired
    private lateinit var leaseService: LeaseService

    @Autowired
    private lateinit var workUnitService: WorkUnitService

    @Test
    fun `renew with a stale fencing token is rejected with LEASE_FENCED`() {
        workUnitService.create(scope, CreateWorkUnitCommand("wu-fence-renew", "task"))
        val acquired = leaseService.acquire(scope, "worker-a", 60_000)!!
        val staleToken = acquired.lease.fencingToken - 1

        val failure = assertThrows(LeaseFencedException::class.java) {
            leaseService.renew(scope, acquired.workUnitId, acquired.lease.leaseId, staleToken, 60_000)
        }

        assertThat(failure.errorCode).isEqualTo("LEASE_FENCED")
    }

    @Test
    fun `release with a stale fencing token is rejected with LEASE_FENCED`() {
        workUnitService.create(scope, CreateWorkUnitCommand("wu-fence-release", "task"))
        val acquired = leaseService.acquire(scope, "worker-a", 60_000)!!
        val staleToken = acquired.lease.fencingToken + 1

        val failure = assertThrows(LeaseFencedException::class.java) {
            leaseService.release(
                scope = scope,
                workUnitId = acquired.workUnitId,
                leaseId = acquired.lease.leaseId,
                fencingToken = staleToken,
                resultStatus = WorkUnitState.COMPLETED,
            )
        }

        assertThat(failure.errorCode).isEqualTo("LEASE_FENCED")
    }

    @Test
    fun `renew on an expired lease is rejected with LEASE_EXPIRED and the reaper requeues it`() {
        workUnitService.create(scope, CreateWorkUnitCommand("wu-expire", "task"))
        val acquired = leaseService.acquire(scope, "worker-a", ttlMs = 1)!!

        Thread.sleep(25)

        val failure = assertThrows(LeaseExpiredException::class.java) {
            leaseService.renew(scope, acquired.workUnitId, acquired.lease.leaseId, acquired.lease.fencingToken, 60_000)
        }
        assertThat(failure.errorCode).isEqualTo("LEASE_EXPIRED")

        val expired = leaseService.expire(scope)
        assertThat(expired).hasSize(1)
        assertThat(expired[0].status).isEqualTo(LeaseStatus.EXPIRED)
        assertThat(expired[0].expiryReason).isEqualTo(LeaseExpiryReasons.HEARTBEAT_TIMEOUT)
        assertThat(expired[0].leaseId).isEqualTo(acquired.lease.leaseId)

        // The reaper re-queues the work unit without double-counting the attempt.
        val requeued = workUnitService.get(scope, acquired.workUnitId)
        assertThat(requeued.status).isEqualTo(WorkUnitState.CREATED)
        assertThat(requeued.attemptCount).isEqualTo(1)
    }

    @Test
    fun `releasing a lease completes the work unit and drops it from the eligible scan`() {
        workUnitService.create(scope, CreateWorkUnitCommand("wu-complete", "task"))
        val acquired = leaseService.acquire(scope, "worker-a", 60_000)!!

        val released = leaseService.release(
            scope = scope,
            workUnitId = acquired.workUnitId,
            leaseId = acquired.lease.leaseId,
            fencingToken = acquired.lease.fencingToken,
            resultStatus = WorkUnitState.COMPLETED,
        )

        assertThat(released.status).isEqualTo(LeaseStatus.RELEASED)
        assertThat(released.releasedAt).isNotNull
        assertThat(workUnitService.get(scope, acquired.workUnitId).status).isEqualTo(WorkUnitState.COMPLETED)
        assertThat(leaseService.acquire(scope, "worker-b", 60_000)).isNull()
    }

    @Test
    fun `acquire returns null and acquireRequired fails with NO_ELIGIBLE_WORK_UNIT`() {
        assertThat(leaseService.acquire(scope, "worker-a", 60_000)).isNull()

        val failure = assertThrows(NoEligibleWorkUnitException::class.java) {
            leaseService.acquireRequired(scope, "worker-a", 60_000)
        }
        assertThat(failure.errorCode).isEqualTo("NO_ELIGIBLE_WORK_UNIT")
    }

    @Test
    fun `a deferred work unit is not eligible before its not_before instant`() {
        val future = java.time.Instant.now().plusSeconds(3600)
        workUnitService.create(
            scope,
            CreateWorkUnitCommand("wu-deferred", "task", notBefore = future),
        )

        assertThat(leaseService.acquire(scope, "worker-a", 60_000)).isNull()
    }
}
