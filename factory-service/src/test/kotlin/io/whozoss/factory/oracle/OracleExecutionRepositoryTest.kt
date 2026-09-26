package io.whozoss.factory.oracle

import io.whozoss.factory.PostgresContainerSpec
import io.whozoss.factory.error.ResourceNotFoundException
import io.whozoss.factory.error.RevisionConflictException
import io.whozoss.factory.oracle.domain.OracleExecution
import io.whozoss.factory.oracle.domain.OracleExecutionKey
import io.whozoss.factory.oracle.domain.OracleExecutionStatus
import io.whozoss.factory.oracle.persistence.OracleExecutionRepository
import io.whozoss.factory.oracle.publisher.OracleArtifactPublisher
import io.whozoss.factory.oracle.service.OracleExecutionService
import io.whozoss.factory.oracle.service.OracleRunCommand
import io.whozoss.factory.persistence.TenantScope
import org.assertj.core.api.Assertions.assertThat
import org.assertj.core.api.Assertions.assertThatThrownBy
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.boot.test.context.SpringBootTest
import org.springframework.boot.test.context.TestConfiguration
import org.springframework.context.annotation.Bean
import org.springframework.context.annotation.Import
import org.springframework.jdbc.core.JdbcTemplate
import org.springframework.test.context.DynamicPropertyRegistry
import org.springframework.test.context.DynamicPropertySource
import org.testcontainers.junit.jupiter.Testcontainers
import java.nio.file.Files
import java.nio.file.Path

/**
 * Testcontainers integration tests of the oracle execution repository and
 * service against a real PostgreSQL instance (V6 `oracle_executions`).
 *
 * Skipped gracefully when no Docker daemon is available.
 */
@SpringBootTest
@Import(OracleArtifactPublisherTestConfiguration::class)
@Testcontainers(disabledWithoutDocker = true)
class OracleExecutionRepositoryTest : PostgresContainerSpec() {

    @Autowired
    private lateinit var repository: OracleExecutionRepository

    @Autowired
    private lateinit var service: OracleExecutionService

    @Autowired
    private lateinit var jdbcTemplate: JdbcTemplate

    private val scope = TenantScope(ORG, WS)

    @BeforeEach
    fun resetFixture() {
        jdbcTemplate.update(
            "DELETE FROM artifacts WHERE organization_id = ? AND workstream_id = ?",
            ORG,
            WS,
        )
        jdbcTemplate.update(
            "DELETE FROM oracle_executions WHERE organization_id = ? AND workstream_id = ?",
            ORG,
            WS,
        )
        jdbcTemplate.update(
            "DELETE FROM workflow_instances WHERE organization_id = ? AND workstream_id = ?",
            ORG,
            WS,
        )
        jdbcTemplate.update(
            """
            INSERT INTO workflow_instances
                (organization_id, workstream_id, namespace_id, workflow_id, instance_json, projection_json)
            VALUES (?, ?, ?, ?, '{}'::jsonb, '{}'::jsonb)
            """.trimIndent(),
            ORG,
            WS,
            NAMESPACE,
            WORKFLOW,
        )
    }

    private fun newExecution(
        executionId: String,
        status: OracleExecutionStatus = OracleExecutionStatus.RUNNING,
        payload: String = "{}",
    ): OracleExecution = OracleExecution(
        organizationId = ORG,
        workstreamId = WS,
        namespaceId = NAMESPACE,
        workflowId = WORKFLOW,
        executionId = executionId,
        oracleId = "smoke",
        status = status,
        payload = payload,
    )

    @Test
    fun `saves a new execution in the running state`() {
        val saved = repository.save(scope, newExecution("exec-running"))

        assertThat(saved.status).isEqualTo(OracleExecutionStatus.RUNNING)
        assertThat(saved.revision).isEqualTo(1)
        val found = repository.findById(scope, OracleExecutionKey(NAMESPACE, WORKFLOW, "exec-running"))
        assertThat(found?.executionId).isEqualTo("exec-running")
    }

    @Test
    fun `terminalizes an execution and increments the revision`() {
        repository.save(scope, newExecution("exec-terminal"))

        val updated = repository.updateStatus(
            scope = scope,
            namespaceId = NAMESPACE,
            workflowId = WORKFLOW,
            executionId = "exec-terminal",
            status = OracleExecutionStatus.SUCCEEDED,
            expectedRevision = 1,
        )

        assertThat(updated.status).isEqualTo(OracleExecutionStatus.SUCCEEDED)
        assertThat(updated.revision).isEqualTo(2)
    }

    @Test
    fun `rejects a stale revision with REVISION_CONFLICT`() {
        repository.save(scope, newExecution("exec-conflict"))
        repository.updateStatus(
            scope, NAMESPACE, WORKFLOW, "exec-conflict", OracleExecutionStatus.SUCCEEDED, 1,
        )

        assertThatThrownBy {
            repository.updateStatus(
                scope, NAMESPACE, WORKFLOW, "exec-conflict", OracleExecutionStatus.FAILED, 1,
            )
        }
            .isInstanceOf(RevisionConflictException::class.java)
            .hasFieldOrPropertyWithValue("errorCode", "REVISION_CONFLICT")
    }

    @Test
    fun `reports NOT_FOUND when terminalizing an unknown execution`() {
        assertThatThrownBy {
            repository.updateStatus(
                scope, NAMESPACE, WORKFLOW, "missing", OracleExecutionStatus.SUCCEEDED, 1,
            )
        }
            .isInstanceOf(ResourceNotFoundException::class.java)
            .hasFieldOrPropertyWithValue("errorCode", "NOT_FOUND")
    }

    @Test
    fun `finds an execution by its idempotency key within the tenant scope`() {
        repository.save(
            scope,
            newExecution("exec-idem", payload = """{"idempotencyKey":"key-42"}"""),
        )

        val found = repository.findByIdempotencyKey(scope, "key-42")
        assertThat(found?.executionId).isEqualTo("exec-idem")
        assertThat(repository.findByIdempotencyKey(TenantScope("other-org", WS), "key-42")).isNull()
    }

    @Test
    fun `isolates executions by tenant scope`() {
        repository.save(scope, newExecution("exec-tenant"))

        val other = repository.findById(
            TenantScope("other-org", "other-ws"),
            OracleExecutionKey(NAMESPACE, WORKFLOW, "exec-tenant"),
        )
        assertThat(other).isNull()
    }

    @Test
    fun `service run creates then idempotently replays an execution`() {
        val command = OracleRunCommand(
            workflowId = WORKFLOW,
            stepId = "verify-code",
            oracleId = "smoke",
            namespaceId = NAMESPACE,
            idempotencyKey = "run-key-1",
        )

        val created = service.run(scope, command)
        assertThat(created.created).isTrue()
        assertThat(created.idempotent).isFalse()
        assertThat(created.status).isEqualTo(OracleExecutionStatus.SUCCEEDED)
        assertThat(created.executionId).isNotBlank()

        val replayed = service.run(scope, command)
        assertThat(replayed.created).isFalse()
        assertThat(replayed.idempotent).isTrue()
        assertThat(replayed.executionId).isEqualTo(created.executionId)
    }

    @Test
    fun `terminalization publishes the linked artifact as available`() {
        repository.save(scope, newExecution("exec-artifact"))
        jdbcTemplate.update(
            """
            INSERT INTO artifacts
                (organization_id, workstream_id, namespace_id, workflow_id, artifact_id,
                 availability_status, content_hash, size, content_type, storage_key)
            VALUES (?, ?, ?, ?, ?, 'pending', 'hash', 1, 'text/plain', 'key')
            """.trimIndent(),
            ORG,
            WS,
            NAMESPACE,
            WORKFLOW,
            "art-1",
        )

        val terminal = service.terminalize(
            scope = scope,
            namespaceId = NAMESPACE,
            workflowId = WORKFLOW,
            executionId = "exec-artifact",
            status = OracleExecutionStatus.SUCCEEDED,
            expectedRevision = 1,
            artifactId = "art-1",
        )

        assertThat(terminal.artifactId).isEqualTo("art-1")
        assertThat(terminal.revision).isEqualTo(2)
        val availability = jdbcTemplate.queryForObject(
            "SELECT availability_status FROM artifacts WHERE artifact_id = ?",
            String::class.java,
            "art-1",
        )
        assertThat(availability).isEqualTo("available")
    }

    companion object {
        private const val ORG = "org-oracle-test"
        private const val WS = "ws-oracle-test"
        private const val NAMESPACE = "ns-oracle-test"
        private const val WORKFLOW = "wf-oracle-test"

        private val smokeDefinition = """
            {
              "schemaVersion": "1",
              "id": "smoke",
              "version": "1.0.0",
              "domain": "factory",
              "argv": ["node", "script.mjs"],
              "cwd": "repo-root",
              "timeoutMs": 10000,
              "success": { "rule": "exit-code", "requireWork": true },
              "applicable": { "workflowTypes": ["oracle-smoke"], "stepIds": ["verify-code"] }
            }
        """.trimIndent()

        private val oracleDefinitionsRoot: Path = Files.createTempDirectory("oracle-repo-definitions").also { root ->
            Files.writeString(root.resolve("smoke@1.0.0.json"), smokeDefinition)
        }

        @JvmStatic
        @DynamicPropertySource
        fun registerOracleDefinitions(registry: DynamicPropertyRegistry) {
            registry.add("factory.oracle.definitions-root") { oracleDefinitionsRoot.toAbsolutePath().toString() }
        }
    }
}

/** Test-only artifact publisher: flips `availability_status` to `available`. */
@TestConfiguration
class OracleArtifactPublisherTestConfiguration {
    @Bean
    fun oracleArtifactPublisher(jdbcTemplate: JdbcTemplate): OracleArtifactPublisher =
        object : OracleArtifactPublisher {
            override fun publishArtifact(
                scope: TenantScope,
                namespaceId: String,
                workflowId: String,
                artifactId: String,
            ) {
                jdbcTemplate.update(
                    """
                    UPDATE artifacts
                       SET availability_status = 'available'
                     WHERE organization_id = ?
                       AND workstream_id = ?
                       AND namespace_id = ?
                       AND workflow_id = ?
                       AND artifact_id = ?
                    """.trimIndent(),
                    scope.organizationId,
                    scope.workstreamId,
                    namespaceId,
                    workflowId,
                    artifactId,
                )
            }
        }
}
