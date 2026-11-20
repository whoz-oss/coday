package io.whozoss.factory.config

import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.AfterAll
import org.junit.jupiter.api.BeforeAll
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.TestInstance
import org.neo4j.driver.AuthTokens
import org.neo4j.driver.Driver
import org.neo4j.driver.GraphDatabase
import org.neo4j.driver.SessionConfig
import org.neo4j.harness.Neo4j
import org.neo4j.harness.Neo4jBuilders
import org.springframework.beans.factory.ObjectProvider
import org.springframework.beans.factory.support.DefaultListableBeanFactory
import org.springframework.boot.autoconfigure.AutoConfigurations
import org.springframework.boot.autoconfigure.data.neo4j.Neo4jDataAutoConfiguration
import org.springframework.boot.test.context.runner.ApplicationContextRunner
import org.springframework.data.neo4j.core.DatabaseSelection
import org.springframework.data.neo4j.core.DatabaseSelectionProvider
import org.springframework.transaction.TransactionDefinition
import org.springframework.transaction.support.AbstractPlatformTransactionManager
import org.springframework.transaction.support.TransactionSynchronizationManager
import org.springframework.transaction.support.TransactionTemplate
import java.lang.reflect.InvocationTargetException
import java.lang.reflect.Proxy
import java.util.concurrent.CopyOnWriteArrayList

/**
 * Proves [Neo4jPersistenceConfiguration] honours `spring.data.neo4j.database`, both for the
 * [org.springframework.data.neo4j.core.Neo4jClient] (repositories and the schema initializers)
 * and for the transaction manager.
 *
 * The in-process harness is Community Edition (single `neo4j` database), so a real second
 * database is not available. Instead the driver is wrapped to record the [SessionConfig] of
 * every session opened: asking for a database that does not exist still opens the session with
 * the requested name before the server rejects it, which is what is asserted.
 */
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
class Neo4jDatabaseSelectionTest {
    private lateinit var harness: Neo4j
    private lateinit var realDriver: Driver
    private lateinit var driver: Driver
    private val sessionConfigs = CopyOnWriteArrayList<SessionConfig>()

    @BeforeAll
    fun startHarness() {
        harness = Neo4jBuilders.newInProcessBuilder().withDisabledServer().build()
        realDriver = GraphDatabase.driver(harness.boltURI(), AuthTokens.none())
        driver =
            Proxy.newProxyInstance(Driver::class.java.classLoader, arrayOf(Driver::class.java)) { _, method, args ->
                (args?.firstOrNull() as? SessionConfig)?.let { sessionConfigs.add(it) }
                try {
                    method.invoke(realDriver, *(args ?: emptyArray()))
                } catch (e: InvocationTargetException) {
                    throw e.targetException
                }
            } as Driver
    }

    @AfterAll
    fun stopHarness() {
        realDriver.close()
        harness.close()
    }

    @BeforeEach
    fun resetRecording() = sessionConfigs.clear()

    @Test
    fun `the standard Spring property is what feeds the selection provider`() {
        ApplicationContextRunner()
            .withConfiguration(AutoConfigurations.of(Neo4jDataAutoConfiguration::class.java))
            .withBean(Driver::class.java, { driver })
            .withPropertyValues("spring.data.neo4j.database=foo")
            .run { context ->
                assertThat(context.getBean(DatabaseSelectionProvider::class.java).getDatabaseSelection())
                    .isEqualTo(DatabaseSelection.byName("foo"))
            }
    }

    @Test
    fun `neo4jClient and transactions both target the configured database`() {
        val provider = DatabaseSelectionProvider { DatabaseSelection.byName("foo") }
        val configuration = Neo4jPersistenceConfiguration()
        val client = configuration.neo4jClient(driver, providerOf(provider))
        val transactionManager = configuration.transactionManager(driver, providerOf(provider))

        runCatching { client.query("RETURN 1").run() }
        assertThat(sessionConfigs).isNotEmpty
        assertThat(sessionConfigs).allSatisfy { assertThat(it.database()).contains("foo") }

        sessionConfigs.clear()
        runCatching { TransactionTemplate(transactionManager).execute { client.query("RETURN 1").run() } }
        assertThat(sessionConfigs).isNotEmpty
        assertThat(sessionConfigs).allSatisfy { assertThat(it.database()).contains("foo") }
    }

    @Test
    fun `without a provider the default database is used and queries succeed`() {
        val configuration = Neo4jPersistenceConfiguration()
        val absent = providerOf(null)
        val client = configuration.neo4jClient(driver, absent)
        val transactionManager = configuration.transactionManager(driver, absent)

        client.query("RETURN 1 AS one").fetch().one()
        TransactionTemplate(transactionManager).execute { client.query("RETURN 1 AS one").fetch().one() }

        assertThat(sessionConfigs).isNotEmpty
        assertThat(sessionConfigs).allSatisfy { assertThat(it.database()).isEmpty }
    }

    @Test
    fun `configured and default database managers synchronize only actual transactions`() {
        val configuration = Neo4jPersistenceConfiguration()
        val named = DatabaseSelectionProvider { DatabaseSelection.byName("foo") }
        for (provider in listOf(named, null)) {
            val manager = configuration.transactionManager(driver, providerOf(provider))
            assertThat(manager.transactionSynchronization)
                .isEqualTo(AbstractPlatformTransactionManager.SYNCHRONIZATION_ON_ACTUAL_TRANSACTION)

            TransactionTemplate(manager).apply {
                propagationBehavior = TransactionDefinition.PROPAGATION_NOT_SUPPORTED
            }.execute {
                assertThat(TransactionSynchronizationManager.isActualTransactionActive()).isFalse()
                assertThat(TransactionSynchronizationManager.isSynchronizationActive()).isFalse()
            }
        }
    }

    private fun providerOf(provider: DatabaseSelectionProvider?): ObjectProvider<DatabaseSelectionProvider> {
        val factory = DefaultListableBeanFactory()
        provider?.let { factory.registerSingleton("databaseSelectionProvider", it) }
        return factory.getBeanProvider(DatabaseSelectionProvider::class.java)
    }
}
