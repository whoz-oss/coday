package io.whozoss.agentos.git

import io.kotest.assertions.throwables.shouldThrow
import io.kotest.core.spec.style.StringSpec
import io.kotest.matchers.shouldBe
import org.springframework.transaction.TransactionDefinition
import org.springframework.transaction.support.AbstractPlatformTransactionManager
import org.springframework.transaction.support.DefaultTransactionStatus
import org.springframework.transaction.support.TransactionTemplate
import java.util.UUID
import java.util.concurrent.CountDownLatch
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import java.util.concurrent.TimeoutException

class WorkspaceLifecycleLocksSpec : StringSpec({
    listOf(false, true).forEach { rollback ->
        "namespace allocation remains coordinated until transaction completion (rollback=$rollback)" {
            val namespaceId = UUID.randomUUID()
            val completing = CountDownLatch(1)
            val allowCompletion = CountDownLatch(1)
            val competitorStarted = CountDownLatch(1)
            val transactions = object : AbstractPlatformTransactionManager() {
                override fun doGetTransaction(): Any = Any()
                override fun doBegin(transaction: Any, definition: TransactionDefinition) = Unit
                override fun doCommit(status: DefaultTransactionStatus) = complete()
                override fun doRollback(status: DefaultTransactionStatus) = complete()
                private fun complete() {
                    completing.countDown()
                    check(allowCompletion.await(5, TimeUnit.SECONDS))
                }
            }
            val executor = Executors.newFixedThreadPool(2)
            try {
                val allocation = executor.submit {
                    TransactionTemplate(transactions).executeWithoutResult { status ->
                        WorkspaceLifecycleLocks.withNamespace(namespaceId) { Unit }
                        if (rollback) status.setRollbackOnly()
                    }
                }
                completing.await(5, TimeUnit.SECONDS) shouldBe true
                val repair = executor.submit<Boolean> {
                    competitorStarted.countDown()
                    WorkspaceLifecycleLocks.withNamespace(namespaceId) { true }
                }
                competitorStarted.await(5, TimeUnit.SECONDS) shouldBe true
                shouldThrow<TimeoutException> { repair.get(200, TimeUnit.MILLISECONDS) }
                // Another namespace remains independent during this transaction's completion.
                WorkspaceLifecycleLocks.withNamespace(UUID.randomUUID()) { true } shouldBe true
                allowCompletion.countDown()
                allocation.get(5, TimeUnit.SECONDS)
                repair.get(5, TimeUnit.SECONDS) shouldBe true
            } finally {
                allowCompletion.countDown()
                executor.shutdownNow()
            }
        }
    }
})
