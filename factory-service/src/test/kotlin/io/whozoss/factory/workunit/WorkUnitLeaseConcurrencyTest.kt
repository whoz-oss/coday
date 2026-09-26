package io.whozoss.factory.workunit

import io.whozoss.factory.DomainIntegrationTest
import io.whozoss.factory.lease.domain.AcquireLeaseResult
import io.whozoss.factory.lease.service.LeaseService
import io.whozoss.factory.workunit.domain.WorkUnitState
import io.whozoss.factory.workunit.service.CreateWorkUnitCommand
import io.whozoss.factory.workunit.service.WorkUnitService
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.boot.test.context.SpringBootTest
import org.testcontainers.junit.jupiter.Testcontainers
import java.util.concurrent.Callable
import java.util.concurrent.CountDownLatch
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit

/**
 * Real-concurrency integration test of the `SELECT ... FOR UPDATE SKIP LOCKED`
 * claim.
 *
 * Many threads acquire a lease simultaneously against a real PostgreSQL
 * instance. If the claim were not `SKIP LOCKED`, the threads would serialise or
 * deadlock; if the eligible scan were not atomic, two threads would claim the
 * same unit. The test asserts that every concurrent acquisition receives a
 * distinct work unit and a distinct, strictly-increasing fencing token that
 * matches the token persisted in `work_unit_leases`.
 */
@SpringBootTest
@Testcontainers(disabledWithoutDocker = true)
class WorkUnitLeaseConcurrencyTest : DomainIntegrationTest() {

    @Autowired
    private lateinit var leaseService: LeaseService

    @Autowired
    private lateinit var workUnitService: WorkUnitService

    @Test
    fun `concurrent acquisitions claim distinct work units and distinct fencing tokens`() {
        val contenders = 10
        repeat(contenders) { index ->
            workUnitService.create(
                scope,
                CreateWorkUnitCommand(workUnitId = "wu-race-$index", unitType = "task", priority = index),
            )
        }

        val pool = Executors.newFixedThreadPool(contenders)
        val startGate = CountDownLatch(1)
        val futures = (0 until contenders).map { index ->
            pool.submit(
                Callable {
                    startGate.await()
                    acquireWithRetry(workerId = "worker-$index", deadlineMs = System.currentTimeMillis() + 20_000)
                },
            )
        }
        startGate.countDown()

        val acquired = futures.map { it.get(30, TimeUnit.SECONDS) }
        pool.shutdown()

        assertThat(acquired).doesNotContainNull()
        assertThat(acquired).hasSize(contenders)

        val workUnitIds = acquired.map { it!!.workUnitId }
        assertThat(workUnitIds.toSet()).hasSize(contenders)

        val tokens = acquired.map { it!!.lease.fencingToken }
        assertThat(tokens.toSet()).hasSize(contenders)
        assertThat(tokens).allMatch { it > 0 }

        // Every acquired work unit was advanced to `running` exactly once.
        acquired.forEach { result ->
            val workUnit = workUnitService.get(scope, result!!.workUnitId)
            assertThat(workUnit.status).isEqualTo(WorkUnitState.RUNNING)
            assertThat(workUnit.attemptCount).isEqualTo(1)

            val persisted = leaseService.findByLeaseId(scope, result.workUnitId, result.lease.leaseId)
            assertThat(persisted).isNotNull
            assertThat(persisted!!.fencingToken).isEqualTo(result.lease.fencingToken)
        }
    }

    @Test
    fun `a single eligible unit is claimed by exactly one concurrent acquisition`() {
        workUnitService.create(scope, CreateWorkUnitCommand(workUnitId = "wu-single", unitType = "task"))

        val contenders = 8
        val pool = Executors.newFixedThreadPool(contenders)
        val startGate = CountDownLatch(1)
        val futures = (0 until contenders).map { index ->
            pool.submit(
                Callable {
                    startGate.await()
                    leaseService.acquire(scope, "worker-$index", 60_000)
                },
            )
        }
        startGate.countDown()
        val results = futures.map { it.get(30, TimeUnit.SECONDS) }
        pool.shutdown()

        val winners = results.filterNotNull()
        assertThat(winners).hasSize(1)
        assertThat(winners[0].workUnitId).isEqualTo("wu-single")
    }

    private fun acquireWithRetry(workerId: String, deadlineMs: Long): AcquireLeaseResult? {
        while (System.currentTimeMillis() < deadlineMs) {
            val result = leaseService.acquire(scope, workerId, 60_000)
            if (result != null) return result
            Thread.sleep(10)
        }
        return null
    }
}
