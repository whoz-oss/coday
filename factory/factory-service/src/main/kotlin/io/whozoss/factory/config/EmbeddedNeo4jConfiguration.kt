package io.whozoss.factory.config

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
 * [org.neo4j.driver.Driver] bean pointing at it over Bolt.
 *
 * Active only when `factory.persistence.mode=embedded-neo4j` and NOT under the
 * `test` profile (tests provide the driver through the Neo4j test harness, which
 * avoids the Netty version conflict of the full BoltServer).
 *
 * ## Why this works with Spring Data Neo4j
 * SDN connects exclusively through the Bolt protocol via the
 * [org.neo4j.driver.Driver] bean. By starting the embedded engine with Bolt
 * enabled on a loopback socket and registering the resulting [Driver] as a
 * Spring bean, SDN is unaware that it talks to an in-process engine instead of a
 * standalone server.
 *
 * The [Driver] is constructed here from the Bolt port actually assigned after
 * startup, so embedded mode does not use `spring.neo4j.uri`. That property remains
 * available for the remote `neo4j` persistence mode.
 *
 * ## Lifecycle
 * The [DatabaseManagementService] is shut down gracefully via
 * [stopEmbeddedNeo4j], registered as a [javax.annotation.PreDestroy] callback.
 */
@Configuration
@EnableConfigurationProperties(PersistenceConfigProperties::class)
@ConditionalOnProperty(name = ["factory.persistence.mode"], havingValue = "embedded-neo4j")
@Profile("!test")
class EmbeddedNeo4jConfiguration(
    private val props: PersistenceConfigProperties,
) {
    private var managementService: DatabaseManagementService? = null

    /**
     * Starts the embedded Neo4j engine and returns a [Driver] connected to it
     * over a loopback Bolt connection.
     *
     * The Bolt connector is bound to `host:port`; when the port is 0 the OS
     * assigns a free port, avoiding conflicts with any existing Neo4j instance.
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
                .setConfig(
                    BoltConnector.advertised_address,
                    SocketAddress(props.embeddedBoltHost, props.embeddedBoltPort),
                ).setConfig(
                    GraphDatabaseSettings.transaction_timeout,
                    Duration.ofSeconds(props.embeddedTransactionTimeoutSeconds),
                ).build()

        managementService = service
        Runtime.getRuntime().addShutdownHook(
            Thread {
                logger.info { "[EmbeddedNeo4j] JVM shutdown hook triggered" }
                service.shutdown()
            },
        )

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
     * instance starts, using the public
     * [org.neo4j.configuration.connectors.ConnectorPortRegister] API.
     */
    private fun resolveBoltPort(service: DatabaseManagementService): Int {
        val graphDb = service.database(GraphDatabaseSettings.DEFAULT_DATABASE_NAME)
        val api = graphDb as GraphDatabaseAPI
        val portRegister =
            api.dependencyResolver.resolveDependency(ConnectorPortRegister::class.java)
        return portRegister.getLocalAddress(ConnectorType.BOLT).port
    }

    companion object : KLogging()
}
