package io.whozoss.factory

import org.springframework.test.context.DynamicPropertyRegistry
import org.springframework.test.context.DynamicPropertySource
import org.testcontainers.containers.PostgreSQLContainer

/**
 * Shared PostgreSQL Testcontainers fixture for integration tests.
 *
 * The container is a static singleton: it is started explicitly once when the
 * companion object is initialized (i.e. before Spring's `@DynamicPropertySource`
 * reads its coordinates) and reused across Spring contexts. We deliberately do
 * not rely on the JUnit `@Testcontainers` / `@Container` extension lifecycle,
 * which does not manage `@Container` fields declared in the companion object of
 * an abstract base class. Its coordinates are injected into the Spring
 * `Environment` through `@DynamicPropertySource`, so every `@SpringBootTest`
 * subclass runs Flyway V1..V7 against a real PostgreSQL 16 instance.
 *
 * The container is never stopped manually: the Testcontainers Ryuk sidecar
 * reaps it at JVM shutdown.
 *
 * Tests that extend this class should also be annotated
 * `@Testcontainers(disabledWithoutDocker = true)` so they are gracefully skipped
 * (rather than failing) on machines without a Docker daemon.
 */
abstract class PostgresContainerSpec {

    /** Concrete container type so the self-typed DSL methods chain cleanly in Kotlin. */
    class FactoryPostgresContainer : PostgreSQLContainer<FactoryPostgresContainer>("postgres:16-alpine")

    companion object {
        @JvmStatic
        protected val postgres: FactoryPostgresContainer =
            FactoryPostgresContainer()
                .withDatabaseName("factory_test")
                .withUsername("factory")
                .withPassword("factory")
                .apply { start() }

        @JvmStatic
        @DynamicPropertySource
        fun registerDatasourceProperties(registry: DynamicPropertyRegistry) {
            registry.add("spring.datasource.url") { postgres.jdbcUrl }
            registry.add("spring.datasource.username") { postgres.username }
            registry.add("spring.datasource.password") { postgres.password }
            registry.add("spring.datasource.driver-class-name") { "org.postgresql.Driver" }
            registry.add("spring.flyway.enabled") { "true" }
            registry.add("spring.flyway.validate-on-migrate") { "true" }
        }
    }
}
