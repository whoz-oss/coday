package io.whozoss.factory.worktree

import io.whozoss.factory.Neo4jDomainIntegrationTest
import io.whozoss.factory.persistence.TenantScope
import io.whozoss.factory.worktree.domain.*
import io.whozoss.factory.worktree.persistence.Neo4jWorktreeExecutionOccupancyRepository
import org.assertj.core.api.Assertions.assertThat
import org.assertj.core.api.Assertions.assertThatThrownBy
import org.junit.jupiter.api.Test
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.data.neo4j.core.Neo4jClient
import org.springframework.transaction.PlatformTransactionManager
import java.util.concurrent.CountDownLatch
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit

class WorktreeExecutionOccupancyRepositoryIntegrationTest : Neo4jDomainIntegrationTest() {
    @Autowired lateinit var client: Neo4jClient
    @Autowired lateinit var transactionManager: PlatformTransactionManager
    private fun repository() = Neo4jWorktreeExecutionOccupancyRepository(client, transactionManager)

    @Test fun `two repository instances racing first acquisition persist one owner`() = race("local-worktree:first", releasedFirst = false)
    @Test fun `two repository instances racing released transfer elect one next owner`() = race("local-worktree:transfer", releasedFirst = true)

    private fun race(resource: String, releasedFirst: Boolean) {
        val seed = repository(); var priorGeneration = 0L
        if (releasedFirst) { val first=seed.acquire(scope,resource,"agent","original"); priorGeneration=first.generation; seed.release(scope,resource,"agent","original",first.generation) }
        val gate=CountDownLatch(1); val pool=Executors.newFixedThreadPool(2)
        try {
            val results=listOf("a" to repository(),"b" to repository()).map { (owner,repo) -> pool.submit<Result<Any>> { gate.await(); runCatching { repo.acquire(scope,resource,"code",owner) } } }.also { gate.countDown() }.map { it.get(20,TimeUnit.SECONDS) }
            val diagnostic=results.joinToString("\n") { it.fold({ "SUCCESS $it" },{ e -> "FAIL ${e.javaClass.name}: ${e.message}; cause=${e.cause}" }) }
            assertThat(results.count { it.isSuccess }).withFailMessage(diagnostic).isEqualTo(1)
            assertThat(results.count { it.exceptionOrNull() is WorktreeExecutionOccupancyConflict }).withFailMessage(diagnostic).isEqualTo(1)
            assertThat(seed.find(resource)!!.generation).isEqualTo(priorGeneration+1)
        } finally { pool.shutdownNow() }
    }

    @Test fun `same owner is idempotent and released transfer increments generation`() {
        val repo=repository(); val first=repo.acquire(scope,"local-worktree:idempotent","agent","x")
        assertThat(repo.acquire(scope,"local-worktree:idempotent","agent","x")).isEqualTo(first)
        repo.release(scope,"local-worktree:idempotent","agent","x",first.generation)
        assertThat(repo.acquire(scope,"local-worktree:idempotent","code","y").generation).isEqualTo(first.generation+1)
    }

    @Test fun `same resource in another scope with same owner id cannot resume or release`() {
        val repo=repository(); val resource="local-worktree:shared"; val first=repo.acquire(scope,resource,"agent","same-id")
        val other=TenantScope("other-org","other-ws")
        assertThatThrownBy { repo.acquire(other,resource,"agent","same-id") }.isInstanceOf(WorktreeExecutionOccupancyConflict::class.java).hasMessage("Worktree resource is already occupied")
        assertThatThrownBy { repo.release(other,resource,"agent","same-id",first.generation) }.isInstanceOf(WorktreeExecutionOccupancyStaleRelease::class.java)
        assertThat(repo.find(resource)).isEqualTo(first)
    }

    @Test fun `stale release after reacquisition is refused`() {
        val repo=repository(); val r="local-worktree:stale"; val first=repo.acquire(scope,r,"agent","one"); repo.release(scope,r,"agent","one",first.generation); val second=repo.acquire(scope,r,"agent","two")
        assertThatThrownBy { repo.release(scope,r,"agent","one",first.generation) }.isInstanceOf(WorktreeExecutionOccupancyStaleRelease::class.java)
        assertThat(repo.find(r)).isEqualTo(second)
    }

    @Test fun `different physical resources and scopes remain independent`() {
        val repo=repository(); val other=TenantScope("other-org","other-ws")
        repo.acquire(scope,"local-worktree:a","agent","one"); repo.acquire(other,"local-worktree:b","agent","one")
        assertThat(repo.find("local-worktree:a")!!.ownerScope).isEqualTo(scope)
        assertThat(repo.find("local-worktree:b")!!.ownerScope).isEqualTo(other)
        assertThat(Neo4jWorktreeExecutionOccupancyRepository.occupancyId("a:b")).isNotEqualTo(Neo4jWorktreeExecutionOccupancyRepository.occupancyId("a"))
    }
}
