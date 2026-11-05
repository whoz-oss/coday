package io.whozoss.factory.worktree

import io.whozoss.factory.Neo4jDomainIntegrationTest
import io.whozoss.factory.environment.domain.WorkEnvironment
import io.whozoss.factory.environment.domain.WorkEnvironmentState
import io.whozoss.factory.environment.persistence.WorkEnvironmentRepository
import io.whozoss.factory.persistence.TenantScope
import io.whozoss.factory.worktree.domain.WorktreeExecutionOccupancyConflict
import io.whozoss.factory.worktree.persistence.Neo4jWorktreeExecutionOccupancyRepository
import org.assertj.core.api.Assertions.assertThat
import org.assertj.core.api.Assertions.assertThatThrownBy
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import org.springframework.beans.factory.annotation.Autowired
import java.nio.file.Files
import java.nio.file.Path

class WorktreeExecutionOccupancyServiceIntegrationTest : Neo4jDomainIntegrationTest() {
    @Autowired lateinit var environments: WorkEnvironmentRepository
    @Autowired lateinit var occupations: Neo4jWorktreeExecutionOccupancyRepository
    @Autowired lateinit var transactionManager: org.springframework.transaction.PlatformTransactionManager
    @TempDir lateinit var temp: Path

    private fun service() = WorktreeExecutionOccupancyService(environments, occupations, transactionManager = transactionManager)

    @Test
    fun `two environment aliases converge to one physical occupation`() {
        val target = Files.createDirectory(temp.resolve("target"))
        val alias = temp.resolve("alias")
        val linked = runCatching { Files.createSymbolicLink(alias, target) }.isSuccess
        org.junit.jupiter.api.Assumptions.assumeTrue(linked, "symbolic links unavailable")
        insert(scope, "env-a", "wf-a", target)
        insert(scope, "env-b", "wf-b", alias)

        val first = service().acquireForEnvironment(scope, "env-a", "agent", "attempt-a")
        assertThatThrownBy { service().acquireForEnvironment(scope, "env-b", "code", "code-b") }
            .isInstanceOf(WorktreeExecutionOccupancyConflict::class.java)
        assertThat(first.resourceIdentity).isEqualTo(LocalWorktreeResourceIdentityResolver().resolve(alias))
    }

    @Test
    fun `same attempt id in another scope cannot resume physical occupation`() {
        val path = Files.createDirectory(temp.resolve("shared"))
        val other = TenantScope("other-org", "other-ws")
        insert(scope, "env-local", "wf-local", path)
        insert(other, "env-other", "wf-other", path)
        service().acquireForEnvironment(scope, "env-local", "agent", "same-attempt")

        assertThatThrownBy { service().acquireForEnvironment(other, "env-other", "agent", "same-attempt") }
            .isInstanceOf(WorktreeExecutionOccupancyConflict::class.java)
    }

    @Test
    fun `missing ambiguous and incoherent paths fail before occupation`() {
        assertThatThrownBy { service().acquireForWorkflow(scope, "missing", "code", "code") }
            .isInstanceOf(WorktreeExecutionOccupancyResolutionException::class.java)
        val real = Files.createDirectory(temp.resolve("real"))
        insert(scope, "env-1", "ambiguous", real)
        insert(scope, "env-2", "ambiguous", real)
        assertThatThrownBy { service().acquireForWorkflow(scope, "ambiguous", "code", "code") }
            .isInstanceOf(WorktreeExecutionOccupancyResolutionException::class.java)
        assertThat(occupations.find(LocalWorktreeResourceIdentityResolver().resolve(real))).isNull()

        val expected = Files.createDirectory(temp.resolve("expected"))
        assertThatThrownBy { service().acquireForEnvironment(scope, "env-1", "code", "code", expected) }
            .isInstanceOf(WorktreeExecutionOccupancyResolutionException::class.java)
        assertThat(occupations.find(LocalWorktreeResourceIdentityResolver().resolve(real))).isNull()
    }

    @Test
    fun `handle releases only its scoped owner and generation`() {
        val path = Files.createDirectory(temp.resolve("release"))
        insert(scope, "env-release", "wf-release", path)
        val handle = service().acquireForEnvironment(scope, "env-release", "code", "code-1", path)
        service().release(handle)
        assertThat(occupations.find(handle.resourceIdentity)!!.status.name).isEqualTo("RELEASED")
    }

    @Test
    fun `stale generation of the same attempt owner cannot release a newer generation`() {
        val path = Files.createDirectory(temp.resolve("stale-same-owner"))
        insert(scope, "env-stale-owner", "wf-stale-owner", path)
        val first = service().acquireForEnvironment(scope, "env-stale-owner", "agent", "attempt-x")
        service().release(first)
        val second = service().acquireForEnvironment(scope, "env-stale-owner", "agent", "attempt-x")

        assertThat(service().releaseOwned(first)).isFalse()
        assertThat(occupations.find(second.resourceIdentity)!!.status.name).isEqualTo("ACTIVE")
        assertThat(service().releaseOwned(second)).isTrue()
    }

    @Test
    fun `attempt acquisition refuses nonready environment and stale revision`() {
        val path = Files.createDirectory(temp.resolve("attempt-ready"))
        insert(scope, "env-attempt", "wf-attempt", path)
        val ready = environments.findByEnvironmentId(scope, "env-attempt")!!
        assertThatThrownBy {
            service().acquireForAttemptEnvironment(scope, ready.environmentId, ready.revision + 1, "agent", "attempt")
        }.isInstanceOf(WorktreeExecutionOccupancyResolutionException::class.java)

        environments.updateState(scope, ready.copy(lifecycleState = WorkEnvironmentState.BUSY), ready.revision, java.time.Instant.now())
        val busy = environments.findByEnvironmentId(scope, ready.environmentId)!!
        assertThatThrownBy {
            service().acquireForAttemptEnvironment(scope, busy.environmentId, busy.revision, "agent", "attempt")
        }.isInstanceOf(WorktreeExecutionOccupancyResolutionException::class.java)
            .hasMessageContaining("not READY")
    }

    private fun insert(ownerScope: TenantScope, environmentId: String, workflowId: String, path: Path) {
        environments.insert(
            ownerScope,
            WorkEnvironment(
                organizationId = ownerScope.organizationId,
                workstreamId = ownerScope.workstreamId,
                environmentId = environmentId,
                workUnitId = "wu-$environmentId",
                workflowId = workflowId,
                namespaceId = "ns-$environmentId",
                repoRoot = path.toString(),
                integrationBranch = "main",
                branch = "branch-$environmentId",
                worktreePath = path.toString(),
                lifecycleState = WorkEnvironmentState.READY,
                createdBy = "test",
            ),
        )
    }
}
