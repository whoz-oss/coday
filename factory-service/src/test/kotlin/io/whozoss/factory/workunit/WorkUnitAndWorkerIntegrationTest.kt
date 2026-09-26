package io.whozoss.factory.workunit

import io.whozoss.factory.DomainIntegrationTest
import io.whozoss.factory.worker.domain.InvalidWorkerTransitionException
import io.whozoss.factory.worker.domain.WorkerState
import io.whozoss.factory.worker.service.RegisterWorkerCommand
import io.whozoss.factory.worker.service.WorkerService
import io.whozoss.factory.workunit.domain.InvalidWorkUnitTransitionException
import io.whozoss.factory.workunit.domain.WorkUnitState
import io.whozoss.factory.workunit.service.CreateWorkUnitCommand
import io.whozoss.factory.workunit.service.WorkUnitService
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Assertions.assertThrows
import org.junit.jupiter.api.Test
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.boot.test.context.SpringBootTest
import java.time.Instant

/**
 * Work-unit state-machine and worker lifecycle integration tests.
 *
 * Exercises the pure state machine rules ported from
 * `factory/src/domain/work-unit.ts` / `factory/src/domain/worker.ts` against
 * PostgreSQL, including the JSONB capabilities array and the heartbeat write.
 */
@SpringBootTest
class WorkUnitAndWorkerIntegrationTest : DomainIntegrationTest() {

    @Autowired
    private lateinit var workUnitService: WorkUnitService

    @Autowired
    private lateinit var workerService: WorkerService

    @Test
    fun `a work unit walks the allowed lifecycle transitions and rejects illegal ones`() {
        val created = workUnitService.create(scope, CreateWorkUnitCommand("wu-states", "task"))
        assertThat(created.status).isEqualTo(WorkUnitState.CREATED)

        val assigned = workUnitService.transition(scope, "wu-states", WorkUnitState.ASSIGNED)
        assertThat(assigned.status).isEqualTo(WorkUnitState.ASSIGNED)
        assertThat(assigned.revision).isEqualTo(2)

        val running = workUnitService.transition(scope, "wu-states", WorkUnitState.RUNNING)
        assertThat(running.status).isEqualTo(WorkUnitState.RUNNING)

        val completed = workUnitService.transition(scope, "wu-states", WorkUnitState.COMPLETED)
        assertThat(completed.status).isEqualTo(WorkUnitState.COMPLETED)

        val failure = assertThrows(InvalidWorkUnitTransitionException::class.java) {
            workUnitService.transition(scope, "wu-states", WorkUnitState.RUNNING)
        }
        assertThat(failure.errorCode).isEqualTo("INVALID_TRANSITION")
    }

    @Test
    fun `an illegal work unit transition from created is rejected`() {
        workUnitService.create(scope, CreateWorkUnitCommand("wu-illegal", "task"))

        assertThrows(InvalidWorkUnitTransitionException::class.java) {
            workUnitService.transition(scope, "wu-illegal", WorkUnitState.RUNNING)
        }
    }

    @Test
    fun `worker registration persists capabilities as a JSONB array and supports heartbeats`() {
        val registered = workerService.register(
            scope,
            RegisterWorkerCommand(
                workerId = "worker-cap",
                workerType = "kubernetes",
                capabilities = listOf("kotlin", "docker"),
                protocolVersion = "1",
            ),
            now = Instant.parse("2020-01-01T00:00:00Z"),
        )
        assertThat(registered.status).isEqualTo(WorkerState.OFFLINE)
        assertThat(registered.capabilities).containsExactly("kotlin", "docker")

        val length = jdbcTemplate.queryForObject(
            "SELECT jsonb_array_length(capabilities) FROM workers WHERE organization_id = ? AND worker_id = ?",
            Int::class.java,
            ORGANIZATION_ID,
            "worker-cap",
        )
        assertThat(length).isEqualTo(2)

        val heartbeatAt = Instant.parse("2020-01-02T00:00:00Z")
        val heartbeat = workerService.heartbeat(scope, "worker-cap", heartbeatAt)
        assertThat(heartbeat.lastHeartbeatAt).isEqualTo(heartbeatAt)
        assertThat(heartbeat.revision).isEqualTo(registered.revision + 1)

        val reloaded = workerService.get(scope, "worker-cap")
        assertThat(reloaded.lastHeartbeatAt).isEqualTo(heartbeatAt)
        assertThat(reloaded.capabilities).containsExactly("kotlin", "docker")
    }

    @Test
    fun `worker state machine allows idle to busy and rejects an illegal transition`() {
        workerService.register(scope, RegisterWorkerCommand("worker-states", "local"))

        val idle = workerService.transition(scope, "worker-states", WorkerState.IDLE)
        assertThat(idle.status).isEqualTo(WorkerState.IDLE)

        val busy = workerService.transition(scope, "worker-states", WorkerState.BUSY)
        assertThat(busy.status).isEqualTo(WorkerState.BUSY)

        val maintenance = workerService.transition(scope, "worker-states", WorkerState.MAINTENANCE)
        assertThat(maintenance.status).isEqualTo(WorkerState.MAINTENANCE)

        val failure = assertThrows(InvalidWorkerTransitionException::class.java) {
            workerService.transition(scope, "worker-states", WorkerState.BUSY)
        }
        assertThat(failure.errorCode).isEqualTo("INVALID_TRANSITION")
    }

    @Test
    fun `worker heartbeat advances the stored revision`() {
        val registered = workerService.register(scope, RegisterWorkerCommand("worker-hb", "local"))

        val first = workerService.heartbeat(scope, "worker-hb")
        val second = workerService.heartbeat(scope, "worker-hb")

        assertThat(first.revision).isEqualTo(registered.revision + 1)
        assertThat(second.revision).isEqualTo(registered.revision + 2)
    }
}
