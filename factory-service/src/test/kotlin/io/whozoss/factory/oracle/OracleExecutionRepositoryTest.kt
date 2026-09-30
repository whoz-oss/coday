package io.whozoss.factory.oracle

import io.whozoss.factory.Neo4jIntegrationTest
import io.whozoss.factory.artifact.domain.ArtifactAvailabilityStatus
import io.whozoss.factory.artifact.infrastructure.persistence.ArtifactMetadataNode
import io.whozoss.factory.artifact.infrastructure.persistence.SpringDataNeo4jArtifactRepository
import io.whozoss.factory.error.ResourceNotFoundException
import io.whozoss.factory.error.RevisionConflictException
import io.whozoss.factory.oracle.domain.OracleExecution
import io.whozoss.factory.oracle.domain.OracleExecutionKey
import io.whozoss.factory.oracle.domain.OracleExecutionStatus
import io.whozoss.factory.oracle.persistence.OracleExecutionRepository
import io.whozoss.factory.oracle.service.OracleExecutionService
import io.whozoss.factory.oracle.service.OracleRunCommand
import io.whozoss.factory.persistence.TenantScope
import org.assertj.core.api.Assertions.assertThat
import org.assertj.core.api.Assertions.assertThatThrownBy
import org.junit.jupiter.api.Test
import org.springframework.beans.factory.annotation.Autowired
import java.time.Instant

/**
 * Embedded-Neo4j integration tests of the oracle execution repository and
 * service (the former `oracle_executions` table is now an `:OracleExecution`
 * node).
 *
 * The Neo4j engine is the in-process test harness — no Docker required.
 */
class OracleExecutionRepositoryTest : Neo4jIntegrationTest() {

    @Autowired
    private lateinit var repository: OracleExecutionRepository

    @Autowired
    private lateinit var service: OracleExecutionService

    @Autowired
    private lateinit var artifactRepository: SpringDataNeo4jArtifactRepository

    private val scope = TenantScope(ORG, WS)

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
        artifactRepository.save(
            ArtifactMetadataNode(
                id = "art-1",
                organizationId = ORG,
                workstreamId = WS,
                namespaceId = NAMESPACE,
                workflowId = WORKFLOW,
                owner = NAMESPACE,
                contentType = "text/plain",
                contentHash = "hash",
                size = 1,
                storageKey = "key",
                availabilityStatus = ArtifactAvailabilityStatus.PENDING.wireValue,
                retentionStatus = "active",
                legalHold = false,
                createdAt = Instant.now(),
                updatedAt = Instant.now(),
            ),
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
        val availability = artifactRepository.findById("art-1").orElseThrow().availabilityStatus
        assertThat(availability).isEqualTo(ArtifactAvailabilityStatus.AVAILABLE.wireValue)
    }

    companion object {
        private const val ORG = "org-oracle-test"
        private const val WS = "ws-oracle-test"
        private const val NAMESPACE = "ns-oracle-test"
        private const val WORKFLOW = "wf-oracle-test"
    }
}
