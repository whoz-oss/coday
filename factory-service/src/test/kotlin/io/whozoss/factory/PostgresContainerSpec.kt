package io.whozoss.factory

import org.junit.jupiter.api.extension.ConditionEvaluationResult
import org.junit.jupiter.api.extension.ExecutionCondition
import org.junit.jupiter.api.extension.ExtendWith
import org.junit.jupiter.api.extension.ExtensionContext
import org.springframework.test.context.DynamicPropertyRegistry
import org.springframework.test.context.DynamicPropertySource
import org.testcontainers.DockerClientFactory
import org.testcontainers.containers.PostgreSQLContainer

/**
 * Shared PostgreSQL Testcontainers fixture for integration tests.
 *
 * The container is a static singleton: it is started explicitly once when the
 * companion object is initialized (i.e. before Spring's `@DynamicPropertySource`
 * reads its coordinates) and reused across Spring contexts. We deliberately do
 * not rely on the JUnit Testcontainers extension lifecycle, which does not
 * manage container fields declared in the companion object of an abstract base
 * class. Its coordinates are injected into the Spring `Environment` through
 * `@DynamicPropertySource`, so every `@SpringBootTest` subclass runs Flyway
 * V1..V7 against a real PostgreSQL 16 instance.
 *
 * The container is never stopped manually: the Testcontainers Ryuk sidecar
 * reaps it at JVM shutdown.
 *
 * [DockerAvailableCondition] is registered (and inherited) through `@ExtendWith`
 * so the whole suite is gracefully skipped — rather than failing — on machines
 * without a Docker daemon, replacing the JUnit Testcontainers
 * `disabledWithoutDocker` behaviour without depending on its container field
 * lifecycle.
 */
@ExtendWith(DockerAvailableCondition::class)
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
            registry.add("spring.datasource.url", postgres::getJdbcUrl)
            registry.add("spring.datasource.username", postgres::getUsername)
            registry.add("spring.datasource.password", postgres::getPassword)
            registry.add("spring.datasource.driver-class-name") { "org.postgresql.Driver" }
            registry.add("spring.flyway.enabled") { "true" }
            registry.add("spring.flyway.validate-on-migrate") { "true" }
        }
    }
}

/**
 * JUnit [ExecutionCondition] that enables Testcontainers-backed integration
 * tests only when a Docker daemon is reachable.
 *
 * Registered via `@ExtendWith` on [PostgresContainerSpec] and inherited by every
 * subclass, it preserves the graceful-skip behaviour previously provided by the
 * JUnit Testcontainers `disabledWithoutDocker` flag, without depending on the
 * Testcontainers extension or any container field.
 */
class DockerAvailableCondition : ExecutionCondition {
    override fun evaluateExecutionCondition(context: ExtensionContext): ConditionEvaluationResult =
        if (DockerClientFactory.instance().isDockerAvailable()) {
            ConditionEvaluationResult.enabled("Docker is available")
        } else {
            ConditionEvaluationResult.disabled("Docker is not available — skipping Testcontainers integration test")
        }
}
