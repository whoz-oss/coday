package io.whozoss.agentos.git

import com.fasterxml.jackson.module.kotlin.jacksonObjectMapper
import io.kotest.core.spec.style.StringSpec
import io.kotest.matchers.shouldBe
import io.mockk.every
import io.mockk.mockk
import io.whozoss.agentos.agent.AgentConfigProperties
import io.whozoss.agentos.caseFlow.Case
import io.whozoss.agentos.caseFlow.CaseConfigProperties
import io.whozoss.agentos.caseFlow.CaseRepository
import io.whozoss.agentos.caseFlow.CaseServiceImpl
import io.whozoss.agentos.caseFlow.CaseWorkspaceProvisioning
import io.whozoss.agentos.caseFlow.InMemoryCaseRepository
import io.whozoss.agentos.integrationConfig.IntegrationConfig
import org.neo4j.configuration.GraphDatabaseSettings
import org.neo4j.dbms.api.DatabaseManagementServiceBuilder
import org.neo4j.graphdb.Transaction
import org.springframework.transaction.TransactionDefinition
import org.springframework.transaction.support.AbstractPlatformTransactionManager
import org.springframework.transaction.support.DefaultTransactionStatus
import org.springframework.transaction.support.TransactionTemplate
import java.nio.file.Files
import java.time.Duration
import java.util.UUID
import java.util.concurrent.CountDownLatch
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit

/** Exercises the actual Namespace relationship lock, not just a fake transaction completion. */
class NamespaceCreationLockSpec : StringSpec({
    "first Git association and case creation acquire the namespace lock before database writes" {
        val directory = Files.createTempDirectory("namespace-creation-lock-")
        val management = DatabaseManagementServiceBuilder(directory)
            .setConfig(GraphDatabaseSettings.transaction_timeout, Duration.ofSeconds(5)).build()
        val db = management.database("neo4j")
        val namespaceId = UUID.randomUUID()
        val case = Case(namespaceId = namespaceId)
        db.beginTx().use { tx ->
            tx.execute("CREATE (:Namespace {id: \$id})", mapOf("id" to namespaceId.toString())).close()
            tx.commit()
        }
        val current = ThreadLocal<Transaction>()
        val transactions = object : AbstractPlatformTransactionManager() {
            override fun doGetTransaction(): Any = Any()
            override fun doBegin(transaction: Any, definition: TransactionDefinition) { current.set(db.beginTx()) }
            override fun doCommit(status: DefaultTransactionStatus) { current.get().commit() }
            override fun doRollback(status: DefaultTransactionStatus) { current.get().rollback() }
            override fun doCleanupAfterCompletion(transaction: Any) { current.get().close(); current.remove() }
        }
        val enteredCreation = CountDownLatch(1)
        val attemptedCaseWrite = CountDownLatch(1)
        val configLockHeld = CountDownLatch(1)
        val allowConfigWrite = CountDownLatch(1)
        val persisted = InMemoryCaseRepository()
        val repository = object : CaseRepository by persisted {
            override fun save(entity: Case): Case {
                current.get()?.let { tx ->
                    attemptedCaseWrite.countDown()
                    tx.execute("CREATE (:Case {id: \$id})", mapOf("id" to entity.id.toString())).close()
                    tx.execute("MATCH (c:Case {id: \$id}), (n:Namespace {id: \$ns}) MERGE (c)-[:BELONGS_TO]->(n)",
                        mapOf("id" to entity.id.toString(), "ns" to namespaceId.toString())).close()
                }
                return persisted.save(entity)
            }
        }
        val settings = GitRepositorySettings(UUID.randomUUID(), namespaceId, "https://forge.example/repo.git", "main", UUID.randomUUID(), true, null)
        val bindings = InMemoryCaseResourceBindingService()
        val provisioning = GitCaseWorkspaceProvisioning(mockk {
            every { findAutomaticSettings(namespaceId) } returns settings
        }, bindings, jacksonObjectMapper(), mockk { every { isAvailable() } returns true })
        val observed = object : CaseWorkspaceProvisioning by provisioning {
            override fun <T> aroundCreation(case: Case, action: () -> T): T {
                enteredCreation.countDown()
                return provisioning.aroundCreation(case, action)
            }
        }
        val service = CaseServiceImpl(mockk(relaxed = true), mockk(relaxed = true), AgentConfigProperties(),
            repository, mockk(relaxed = true), mockk(relaxed = true), mockk(relaxed = true), CaseConfigProperties(),
            mockk(relaxed = true), mockk(relaxed = true), mockk(relaxed = true), observed)
        val policy = GitRepositoryConfigPolicy(mockk(), mockk(), mockk(), mockk())
        val config = IntegrationConfig(namespaceId = namespaceId, name = "git", integrationType = GitRepositoryIntegration.TYPE)
        val executor = Executors.newFixedThreadPool(2)
        try {
            val association = executor.submit {
                policy.aroundSave(config) {
                    configLockHeld.countDown()
                    check(allowConfigWrite.await(5, TimeUnit.SECONDS))
                    db.beginTx().use { tx ->
                        tx.execute("CREATE (:IntegrationConfig {id: 'test-config'})").close()
                        tx.execute("MATCH (c:IntegrationConfig {id: 'test-config'}), (n:Namespace {id: \$ns}) MERGE (c)-[:BELONGS_TO]->(n)",
                            mapOf("ns" to namespaceId.toString())).close()
                        tx.commit()
                    }
                }
            }
            configLockHeld.await(5, TimeUnit.SECONDS) shouldBe true
            val creation = executor.submit<Case> { TransactionTemplate(transactions).execute { service.create(case) }!! }
            enteredCreation.await(5, TimeUnit.SECONDS) shouldBe true
            attemptedCaseWrite.await(200, TimeUnit.MILLISECONDS) shouldBe false
            allowConfigWrite.countDown()
            association.get(8, TimeUnit.SECONDS)
            creation.get(8, TimeUnit.SECONDS).id shouldBe case.id
            bindings.findByRootCaseId(case.id)?.settingsJson shouldBe jacksonObjectMapper().writeValueAsString(settings)
            db.beginTx().use { tx ->
                tx.execute("MATCH (:Namespace {id: \$ns})<-[:BELONGS_TO]-(e) RETURN count(e) AS count", mapOf("ns" to namespaceId.toString())).use {
                    it.next()["count"] shouldBe 2L
                }
            }
        } finally {
            allowConfigWrite.countDown()
            executor.shutdownNow()
            executor.awaitTermination(12, TimeUnit.SECONDS)
            service.shutdown()
            management.shutdown()
            directory.toFile().deleteRecursively()
        }
    }
})
