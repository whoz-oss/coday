package io.whozoss.agentos.persistence.neo4j

import mu.KLogging
import org.neo4j.driver.AuthTokens
import org.neo4j.driver.Driver
import org.neo4j.driver.GraphDatabase
import org.neo4j.harness.Neo4j
import org.springframework.boot.test.context.TestConfiguration
import org.springframework.context.annotation.Bean
import org.springframework.context.annotation.Primary
import org.springframework.context.annotation.Profile

/**
 * Test-only Spring configuration that replaces [io.whozoss.agentos.config.EmbeddedNeo4jConfiguration] for
 * the `embedded-neo4j` profile in tests.
 *
 * Uses the Neo4j test harness ([org.neo4j.harness.Neo4j]) instead of the full
 * embedded engine. The harness starts an in-process Neo4j instance that exposes
 * a Bolt URI without requiring Netty 4.2, which avoids the version conflict
 * between Neo4j 2026.x (needs Netty 4.2) and Spring Boot 3.x (pins Netty 4.1).
 *
 * The [Neo4j] bean is the run-wide [EmbeddedNeo4jHarness] and must outlive every test context, so its
 * `destroyMethod` is empty: left unset, `@Bean` infers `close()` and the first context to be torn down
 * would take the database away from all the others. That class documents what a shared database asks
 * of a spec. The [Driver] belongs to the context instead, and is closed with it.
 *
 * The [Driver] bean is marked [@Primary] so it wins over any driver bean
 * registered by Spring Boot's Neo4j auto-configuration.
 */
@TestConfiguration
@Profile("embedded-neo4j")
class EmbeddedNeo4jTestConfiguration {
    @Bean(destroyMethod = "") // see the class KDoc: inferring close() would shut the shared harness down
    fun neo4jHarness(): Neo4j = EmbeddedNeo4jHarness.instance

    @Bean(destroyMethod = "close")
    @Primary
    fun driver(harness: Neo4j): Driver {
        val driver = GraphDatabase.driver(harness.boltURI(), AuthTokens.none())
        driver.verifyConnectivity()
        logger.info { "[EmbeddedNeo4j-Test] Driver connected to ${harness.boltURI()}" }
        return driver
    }

    companion object : KLogging()
}
