package io.whozoss.factory.worktree

import io.whozoss.factory.worktree.domain.WorktreeExecutionOccupancyConflict
import io.whozoss.factory.worktree.persistence.Neo4jWorktreeExecutionOccupancyRepository
import io.mockk.mockk
import org.assertj.core.api.Assertions.assertThat
import org.assertj.core.api.Assertions.assertThatThrownBy
import org.junit.jupiter.api.Test
import org.springframework.dao.TransientDataAccessResourceException
import org.springframework.data.neo4j.core.Neo4jClient
import org.springframework.transaction.PlatformTransactionManager
import java.util.concurrent.atomic.AtomicInteger

class WorktreeExecutionOccupancyDeadlockRetryTest {
    private val repository = Neo4jWorktreeExecutionOccupancyRepository(
        mockk<Neo4jClient>(relaxed = true),
        mockk<PlatformTransactionManager>(relaxed = true),
    )

    @Test
    fun `recognized Neo4j deadlock retries the complete block and returns the later result`() {
        val calls = AtomicInteger()
        val result = repository.executeRetryingDeadlock {
            if (calls.incrementAndGet() == 1) throw deadlock()
            "won-after-fresh-transaction"
        }
        assertThat(result).isEqualTo("won-after-fresh-transaction")
        assertThat(calls.get()).isEqualTo(2)
    }

    @Test
    fun `business conflict is never retried`() {
        val calls = AtomicInteger()
        assertThatThrownBy {
            repository.executeRetryingDeadlock<String> {
                calls.incrementAndGet()
                throw WorktreeExecutionOccupancyConflict("occupied")
            }
        }.isInstanceOf(WorktreeExecutionOccupancyConflict::class.java)
        assertThat(calls.get()).isEqualTo(1)
    }

    @Test
    fun `unrecognized transient or ordinary error is never retried`() {
        val calls = AtomicInteger()
        val failure = IllegalStateException("other failure")
        assertThatThrownBy {
            repository.executeRetryingDeadlock<String> {
                calls.incrementAndGet()
                throw failure
            }
        }.isSameAs(failure)
        assertThat(calls.get()).isEqualTo(1)
    }

    @Test
    fun `deadlock retry is bounded and rethrows the last failure`() {
        val calls = AtomicInteger()
        var last: Throwable? = null
        assertThatThrownBy {
            repository.executeRetryingDeadlock<String> {
                calls.incrementAndGet()
                deadlock().also { last = it }.let { throw it }
            }
        }.isSameAs(last)
        assertThat(calls.get()).isEqualTo(Neo4jWorktreeExecutionOccupancyRepository.MAX_DEADLOCK_ATTEMPTS)
    }

    @Test
    fun `deadlock is recognized through wrapped cause chain only by exact Neo4j code`() {
        assertThat(repository.isNeo4jDeadlock(deadlock())).isTrue()
        assertThat(repository.isNeo4jDeadlock(TransientDataAccessResourceException("transient but not deadlock"))).isFalse()
    }

    private fun deadlock(): Throwable = TransientDataAccessResourceException(
        "translated",
        RuntimeException("Neo.TransientError.Transaction.DeadlockDetected: lock cycle"),
    )
}
