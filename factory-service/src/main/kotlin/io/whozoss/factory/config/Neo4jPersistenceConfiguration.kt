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
 * Spring Boot's `Neo4jDataAutoConfiguration` only creates a
 * [Neo4jTransactionManager] when no other `PlatformTransactionManager` exists. The
 * interim H2 datasource (for the not-yet-migrated relational repositories) creates
 * a `DataSourceTransactionManager`, which makes Spring Boot skip the Neo4j one —
 * leaving `Neo4jTemplate` without a `transactionTemplate` and failing every
 * repository write. Declaring it here restores transactional repository
 * operations. It is intentionally named `transactionManager` so the default
 * `@Transactional` lookup resolves to Neo4j (now the authoritative store) and
 * Spring Boot's datasource manager backs off.
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
    ],
)
class Neo4jPersistenceConfiguration {

    @Bean
    @Primary
    fun transactionManager(driver: Driver): Neo4jTransactionManager =
        Neo4jTransactionManager(driver, DatabaseSelectionProvider.getDefaultSelectionProvider())
}
