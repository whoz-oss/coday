package io.whozoss.factory.config

import org.neo4j.driver.Driver
import org.springframework.context.annotation.Bean
import org.springframework.context.annotation.Configuration
import org.springframework.context.annotation.Primary
import org.springframework.data.neo4j.core.DatabaseSelectionProvider
import org.springframework.data.neo4j.core.transaction.Neo4jTransactionManager
import org.springframework.data.neo4j.repository.config.EnableNeo4jRepositories

/**
 * Enables the Spring Data Neo4j repositories backing the migrated aggregates and
 * registers the Neo4j transaction manager.
 *
 * Active for both persistence modes that use a live Neo4j engine:
 * - `neo4j`          standalone server (Docker / remote); Driver from Spring Boot auto-config
 * - `embedded-neo4j` in-process engine; Driver from [EmbeddedNeo4jConfiguration]
 *
 * The repository Adapter beans themselves ([io.whozoss.factory.oracle.persistence.Neo4jOracleExecutionRepository],
 * [io.whozoss.factory.artifact.infrastructure.persistence.Neo4jArtifactStore]) are regular
 * Spring components; this configuration only switches on the derived Spring Data
 * repository interfaces they delegate to.
 *
 * ## Why the transaction manager is declared explicitly
 * Declaring it keeps transactional semantics deterministic and independent of
 * auto-configuration ordering, and guarantees the default `@Transactional`
 * lookup resolves to the Neo4j transaction manager — the only
 * `PlatformTransactionManager` left now that the relational store is gone.
 */
@Configuration
@EnableNeo4jRepositories(
    basePackages = [
        "io.whozoss.factory.oracle.persistence",
        "io.whozoss.factory.artifact.infrastructure.persistence",
        "io.whozoss.factory.delivery.persistence",
        "io.whozoss.factory.lease.persistence",
        "io.whozoss.factory.worker.persistence",
        "io.whozoss.factory.workunit.persistence",
        "io.whozoss.factory.environment.persistence",
        "io.whozoss.factory.agentattempt.persistence",
        "io.whozoss.factory.workflow.persistence",
        "io.whozoss.factory.workstream.persistence",
    ],
)
class Neo4jPersistenceConfiguration {

    @Bean
    @Primary
    fun transactionManager(driver: Driver): Neo4jTransactionManager =
        Neo4jTransactionManager(driver, DatabaseSelectionProvider.getDefaultSelectionProvider())
}
