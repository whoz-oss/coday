package io.whozoss.factory.config

import org.neo4j.driver.Driver
import org.springframework.beans.factory.ObjectProvider
import org.springframework.context.annotation.Bean
import org.springframework.context.annotation.Configuration
import org.springframework.context.annotation.Primary
import org.springframework.data.neo4j.core.DatabaseSelectionProvider
import org.springframework.data.neo4j.core.Neo4jClient
import org.springframework.data.neo4j.core.Neo4jTemplate
import org.springframework.data.neo4j.core.convert.Neo4jConversions
import org.springframework.data.neo4j.core.mapping.Neo4jMappingContext
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
 *
 * ## Database selection
 * The [Neo4jClient] and the transaction manager are both built with the Spring Boot
 * [DatabaseSelectionProvider], which is bound to the standard property
 * `spring.data.neo4j.database` (env `SPRING_DATA_NEO4J_DATABASE`). This lets Factory and
 * AgentOS live in separate named databases of a shared multi-database server. When the
 * property is unset the provider selects the server default database, which is also the only
 * choice in embedded mode (Community Edition has a single user database). Any code that talks
 * to Neo4j must go through the [Neo4jClient] (or a repository), never through a raw
 * `driver.session()`, otherwise it would silently target the default database.
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
        "io.whozoss.factory.planchange.persistence",
        "io.whozoss.factory.run.persistence",
    ],
)
class Neo4jPersistenceConfiguration {

    /**
     * Registers the [Neo4jStringToInstantConverter] so SDN can read date
     * properties stored as ISO-8601 String values by legacy versions of the
     * service, alongside the native ZonedDateTime values written by current
     * versions. No writing converter is added: new writes always use the
     * native type.
     */
    @Bean
    fun neo4jConversions(): Neo4jConversions = Neo4jConversions(setOf(Neo4jStringToInstantConverter()))

    @Bean
    fun neo4jClient(
        driver: Driver,
        databaseSelectionProvider: ObjectProvider<DatabaseSelectionProvider>,
    ): Neo4jClient =
        Neo4jClient
            .with(driver)
            .withDatabaseSelectionProvider(resolve(databaseSelectionProvider))
            .build()

    @Bean
    fun neo4jTemplate(
        neo4jClient: Neo4jClient,
        neo4jMappingContext: Neo4jMappingContext,
    ): Neo4jTemplate = Neo4jTemplate(neo4jClient, neo4jMappingContext)

    @Bean
    @Primary
    fun transactionManager(
        driver: Driver,
        databaseSelectionProvider: ObjectProvider<DatabaseSelectionProvider>,
    ): Neo4jTransactionManager =
        Neo4jTransactionManager
            .with(driver)
            .withDatabaseSelectionProvider(resolve(databaseSelectionProvider))
            .build()

    /** Spring Boot's provider (`spring.data.neo4j.database`), or the default database when absent. */
    private fun resolve(provider: ObjectProvider<DatabaseSelectionProvider>): DatabaseSelectionProvider =
        provider.getIfAvailable { DatabaseSelectionProvider.getDefaultSelectionProvider() }
}
