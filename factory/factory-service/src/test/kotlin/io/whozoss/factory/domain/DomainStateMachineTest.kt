package io.whozoss.factory.domain

import io.whozoss.factory.environment.domain.WorkEnvironmentState
import io.whozoss.factory.lease.domain.LeaseStatus
import io.whozoss.factory.lease.domain.WorkUnitLease
import io.whozoss.factory.worker.domain.WorkerState
import io.whozoss.factory.workunit.domain.WorkUnitState
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test
import java.time.Instant

/**
 * Pure (no database, no Docker) unit tests of the lifecycle state machines and
 * the lease-expiry rule shared by the SQL adapters.
 */
class DomainStateMachineTest {

    @Test
    fun `work unit transitions follow the ported state machine`() {
        assertThat(WorkUnitState.CREATED.canTransitionTo(WorkUnitState.ASSIGNED)).isTrue()
        assertThat(WorkUnitState.CREATED.canTransitionTo(WorkUnitState.RUNNING)).isFalse()
        assertThat(WorkUnitState.RUNNING.canTransitionTo(WorkUnitState.COMPLETED)).isTrue()
        assertThat(WorkUnitState.COMPLETED.terminal).isTrue()
        assertThat(WorkUnitState.COMPLETED.canTransitionTo(WorkUnitState.RUNNING)).isFalse()
        assertThat(WorkUnitState.fromDbValue("running")).isEqualTo(WorkUnitState.RUNNING)
    }

    @Test
    fun `worker transitions follow the ported state machine`() {
        assertThat(WorkerState.OFFLINE.canTransitionTo(WorkerState.IDLE)).isTrue()
        assertThat(WorkerState.IDLE.canTransitionTo(WorkerState.BUSY)).isTrue()
        assertThat(WorkerState.BUSY.canTransitionTo(WorkerState.IDLE)).isTrue()
        assertThat(WorkerState.MAINTENANCE.canTransitionTo(WorkerState.BUSY)).isFalse()
        assertThat(WorkerState.fromDbValue("maintenance")).isEqualTo(WorkerState.MAINTENANCE)
    }

    @Test
    fun `environment transitions follow provisioning to ready to busy to decommissioned`() {
        assertThat(WorkEnvironmentState.PROVISIONING.canTransitionTo(WorkEnvironmentState.READY)).isTrue()
        assertThat(WorkEnvironmentState.READY.canTransitionTo(WorkEnvironmentState.BUSY)).isTrue()
        assertThat(WorkEnvironmentState.BUSY.canTransitionTo(WorkEnvironmentState.DECOMMISSIONED)).isTrue()
        assertThat(WorkEnvironmentState.DECOMMISSIONED.terminal).isTrue()
        assertThat(WorkEnvironmentState.DECOMMISSIONED.canTransitionTo(WorkEnvironmentState.READY)).isFalse()
    }

    @Test
    fun `an active lease past its deadline is expired by time`() {
        val now = Instant.parse("2020-01-01T00:00:00Z")
        val expired = WorkUnitLease(
            organizationId = "org",
            workstreamId = "ws",
            workUnitId = "wu",
            leaseId = "lease-1",
            workerId = "worker",
            status = LeaseStatus.ACTIVE,
            fencingToken = 1,
            leaseExpiresAt = now.minusMillis(1),
        )
        assertThat(expired.isExpiredByTime(now)).isTrue()

        val live = expired.copy(leaseExpiresAt = now.plusSeconds(60))
        assertThat(live.isExpiredByTime(now)).isFalse()
        assertThat(live.isActive(now)).isTrue()
    }
}
