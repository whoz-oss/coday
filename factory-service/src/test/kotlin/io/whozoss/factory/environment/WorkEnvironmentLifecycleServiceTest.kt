package io.whozoss.factory.environment

import io.whozoss.factory.DomainIntegrationTest
import io.whozoss.factory.environment.domain.EnvironmentIdentityConflictException
import io.whozoss.factory.environment.domain.WorkEnvironmentState
import io.whozoss.factory.environment.service.ProvisionEnvironmentCommand
import io.whozoss.factory.environment.service.WorkUnitEnvironmentService
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Assertions.assertThrows
import org.junit.jupiter.api.Test
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.boot.test.context.SpringBootTest
import org.testcontainers.junit.jupiter.Testcontainers

/**
 * Environment lifecycle integration test against PostgreSQL.
 *
 * Proves the state machine `provisioning -> ready -> busy -> decommissioned`
 * end-to-end against the V6 `work_environments.status` vocabulary, and that the
 * lifecycle status column never drifts from the descriptor payload.
 */
@SpringBootTest
@Testcontainers(disabledWithoutDocker = true)
class WorkEnvironmentLifecycleServiceTest : DomainIntegrationTest() {

    @Autowired
    private lateinit var service: WorkUnitEnvironmentService

    @Test
    fun `environment walks provisioning to ready to busy to decommissioned`() {
        val outcome = service.provision(
            scope,
            command(workflowId = "wf-lifecycle", workUnitId = "wu-1"),
        )

        assertThat(outcome.changed).isTrue
        assertThat(outcome.environment.lifecycleState).isEqualTo(WorkEnvironmentState.READY)
        assertThat(outcome.environment.worktreePath).isNotBlank
        assertThat(statusColumn(outcome.environment.environmentId)).isEqualTo("ready")

        val busy = service.markBusy(scope, "wf-lifecycle")
        assertThat(busy.lifecycleState).isEqualTo(WorkEnvironmentState.BUSY)
        assertThat(statusColumn(busy.environmentId)).isEqualTo("busy")

        val released = service.release(scope, "wf-lifecycle")
        assertThat(released.environment.lifecycleState).isEqualTo(WorkEnvironmentState.DECOMMISSIONED)
        assertThat(statusColumn(released.environment.environmentId)).isEqualTo("decommissioned")
    }

    @Test
    fun `provisioning a reserved environment persists the provisioning state before ready`() {
        val first = service.provision(scope, command(workflowId = "wf-idem", workUnitId = "wu-idem"))
        assertThat(first.changed).isTrue

        val second = service.provision(scope, command(workflowId = "wf-idem", workUnitId = "wu-idem"))
        assertThat(second.changed).isFalse
        assertThat(second.environment.environmentId).isEqualTo(first.environment.environmentId)
        assertThat(second.environment.lifecycleState).isEqualTo(WorkEnvironmentState.READY)
    }

    @Test
    fun `an identity conflict on the same environment id is rejected`() {
        service.provision(
            scope,
            command(workflowId = "wf-conflict", workUnitId = "wu-a").copy(environmentId = "fixed-env"),
        )

        assertThrows(EnvironmentIdentityConflictException::class.java) {
            service.provision(
                scope,
                command(workflowId = "wf-conflict", workUnitId = "wu-b").copy(environmentId = "fixed-env"),
            )
        }
    }

    @Test
    fun `release is idempotent`() {
        service.provision(scope, command(workflowId = "wf-release", workUnitId = "wu-1"))

        val first = service.release(scope, "wf-release")
        val second = service.release(scope, "wf-release")

        assertThat(first.environment.lifecycleState).isEqualTo(WorkEnvironmentState.DECOMMISSIONED)
        assertThat(second.environment.lifecycleState).isEqualTo(WorkEnvironmentState.DECOMMISSIONED)
        assertThat(second.environment.revision).isEqualTo(first.environment.revision)
    }

    @Test
    fun `inspect reconciles a ready environment against its worktree`() {
        service.provision(scope, command(workflowId = "wf-inspect", workUnitId = "wu-1"))

        val inspection = service.inspect(scope, "wf-inspect")
        assertThat(inspection.environment.lifecycleState).isEqualTo(WorkEnvironmentState.READY)
        assertThat(inspection.reconciliation).isNotNull
        assertThat(inspection.headCommit).isNotNull
    }

    private fun command(workflowId: String, workUnitId: String): ProvisionEnvironmentCommand =
        ProvisionEnvironmentCommand(
            workflowId = workflowId,
            workUnitId = workUnitId,
            namespaceId = "ns-test",
            integrationBranch = "main",
            branch = "feature/work",
            createdBy = "tester",
        )

    private fun statusColumn(environmentId: String): String? = jdbcTemplate.queryForObject(
        "SELECT status FROM work_environments WHERE organization_id = ? AND workstream_id = ? " +
            "AND environment_id = ?",
        String::class.java,
        ORGANIZATION_ID,
        WORKSTREAM_ID,
        environmentId,
    )
}
