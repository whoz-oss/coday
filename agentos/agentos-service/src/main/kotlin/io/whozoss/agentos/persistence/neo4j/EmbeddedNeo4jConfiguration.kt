package io.whozoss.agentos.persistence.neo4j

import io.whozoss.agentos.config.PersistenceConfigProperties
import mu.KLogging
import org.neo4j.configuration.GraphDatabaseSettings
import org.neo4j.configuration.connectors.BoltConnector
import org.neo4j.configuration.connectors.ConnectorPortRegister
import org.neo4j.configuration.connectors.ConnectorType
import org.neo4j.configuration.helpers.SocketAddress
import org.neo4j.dbms.api.DatabaseManagementService
import org.neo4j.dbms.api.DatabaseManagementServiceBuilder
import org.neo4j.driver.AuthTokens
import org.neo4j.driver.Driver
import org.neo4j.driver.GraphDatabase
import org.neo4j.kernel.internal.GraphDatabaseAPI
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty
import org.springframework.boot.context.properties.EnableConfigurationProperties
import org.springframework.context.annotation.Bean
import org.springframework.context.annotation.Configuration
import org.springframework.context.annotation.Profile
import java.nio.file.Path
import java.time.Duration
import javax.annotation.PreDestroy

/**
 * Starts an embedded Neo4j Community Edition instance and registers a
 * [Driver] bean pointing at it over Bolt (loopback, random port).
 *
 * Active only when `agentos.persistence.mode=embedded-neo4j`.
 *
 * ## Why this works with Spring Data Neo4j
 * SDN connects exclusively through the Bolt protocol via the [Driver] bean.
 * By starting the embedded engine with Bolt enabled on a loopback socket and
 * registering the resulting [Driver] as a Spring bean, SDN is unaware that it
 * is talking to an in-process engine rather than a standalone server.
 * All repository operations, transactions, and Cypher queries work identically.
 *
 * Crucially, `spring.neo4j.uri` does NOT need to be set in embedded mode.
 * Spring Boot's Neo4j auto-configuration detects that a [Driver] bean is already
 * present and uses it directly, skipping its own URI-based driver creation entirely.
 * The URI is only required in `neo4j` mode where Spring Boot creates the [Driver].
 *
 * ## Data directory
 * The embedded database files are stored under
 * `<agentos.persistence.data-dir>/neo4j/`. Relative paths are resolved against
 * the JVM working directory.
 *
 * ## Lifecycle
 * The [DatabaseManagementService] is shut down gracefully via [stopEmbeddedNeo4j],
 * which is registered as a [jakarta.annotation.PreDestroy] callback.
 *
 * ## Docker prerequisite
 * This mode eliminates the Docker prerequisite for local single-user deployments.
 * Users run `java -jar agentos-service.jar` and the database starts automatically.
 *
 * ## Production deployments
 * For multi-user server deployments, use `agentos.persistence.mode=neo4j` and
 * point `spring.neo4j.uri` at a standalone Neo4j server instead.
 */
@Configuration
@EnableConfigurationProperties(PersistenceConfigProperties::class)
@ConditionalOnProperty(name = ["agentos.persistence.mode"], havingValue = "embedded-neo4j")
@Profile("!test") // In tests, EmbeddedNeo4jTestConfiguration provides the Driver via the harness
class EmbeddedNeo4jConfiguration(
    private val props: PersistenceConfigProperties,
) {
    private var managementService: DatabaseManagementService? = null

    /**
     * Starts the embedded Neo4j engine and returns a [Driver] connected to it
     * over a loopback Bolt connection.
     *
     * The Bolt connector is bound to `localhost:0` so the OS assigns a free port,
     * avoiding conflicts with any existing Neo4j installation on the machine.
     *
     * Spring Data Neo4j's auto-configuration detects this [Driver] bean and uses
     * it for all repository operations — no `spring.neo4j.uri` property needed.
     */
    @Bean(destroyMethod = "close")
    fun driver(): Driver {
        val dbDir =
            Path
                .of(props.dataDir)
                .toAbsolutePath()
                .normalize()
                .resolve("neo4j")

        logger.info { "[EmbeddedNeo4j] Starting embedded Neo4j at $dbDir" }

        val service =
            DatabaseManagementServiceBuilder(dbDir)
                .setConfig(BoltConnector.enabled, true)
                .setConfig(BoltConnector.listen_address, SocketAddress(props.embeddedBoltHost, props.embeddedBoltPort))
                .setConfig(BoltConnector.advertised_address, SocketAddress(props.embeddedBoltHost, props.embeddedBoltPort))
                .setConfig(GraphDatabaseSettings.transaction_timeout, Duration.ofSeconds(props.embeddedTransactionTimeoutSeconds))
                .build()

        managementService = service
        Runtime.getRuntime().addShutdownHook(Thread {
            logger.info { "[EmbeddedNeo4j] JVM shutdown hook triggered" }
            service.shutdown()
        })

        val boltPort = resolveBoltPort(service)
        val boltUri = "bolt://${props.embeddedBoltHost}:$boltPort"
        logger.info { "[EmbeddedNeo4j] Bolt listening on $boltUri" }

        val driver = GraphDatabase.driver(boltUri, AuthTokens.none())
        driver.verifyConnectivity()
        logger.info { "[EmbeddedNeo4j] Driver connected" }

        return driver
    }

    @PreDestroy
    fun stopEmbeddedNeo4j() {
        logger.info { "[EmbeddedNeo4j] Shutting down..." }
        managementService?.shutdown()
        managementService = null
        logger.info { "[EmbeddedNeo4j] Shut down complete" }
    }

    /**
     * Resolves the actual Bolt port assigned by the OS after the embedded
     * instance starts, using the public [ConnectorPortRegister] API.
     */
    private fun resolveBoltPort(service: DatabaseManagementService): Int {
        val graphDb = service.database(GraphDatabaseSettings.DEFAULT_DATABASE_NAME)
        // GraphDatabaseAPI is the internal interface that exposes the DependencyResolver;
        // it is part of the public kernel API surface (not subject to removal).
        val api = graphDb as GraphDatabaseAPI
        val portRegister =
            api.dependencyResolver.resolveDependency(ConnectorPortRegister::class.java)
        return portRegister.getLocalAddress(ConnectorType.BOLT).port
    }

    companion object : KLogging()
}
