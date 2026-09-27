package io.whozoss.factory

import io.whozoss.factory.oracle.publisher.OracleArtifactPublisher
import io.whozoss.factory.persistence.TenantScope
import org.junit.jupiter.api.extension.ConditionEvaluationResult
import org.junit.jupiter.api.extension.ExecutionCondition
import org.junit.jupiter.api.extension.ExtendWith
import org.junit.jupiter.api.extension.ExtensionContext
import org.springframework.boot.test.context.SpringBootTest
import org.springframework.boot.test.context.TestConfiguration
import org.springframework.context.annotation.Bean
import org.springframework.jdbc.core.JdbcTemplate
import org.springframework.test.context.DynamicPropertyRegistry
import org.springframework.test.context.DynamicPropertySource
import org.testcontainers.DockerClientFactory
import org.testcontainers.containers.PostgreSQLContainer
import java.nio.file.Files
import java.nio.file.Path

/**
 * Single shared Spring Boot integration fixture for every `factory-service`
 * integration test.
 *
 * A single, canonical [SpringBootTest] configuration is declared here — a real
 * servlet container on a random port, plus the shared [SharedIntegrationTestConfiguration]
 * — and *inherited* by every subclass. Because the annotation is `@Inherited`
 * and Spring Test resolves it through the test-class hierarchy, all integration
 * tests derive the exact same `MergedContextConfiguration`. Spring Test then
 * reuses ONE cached `ApplicationContext` for the whole suite, so the
 * `DataSource` / `HikariPool` is created once and never closed mid-suite by
 * context-cache eviction.
 *
 * The PostgreSQL Testcontainers fixture is a static singleton: it is started
 * explicitly once when the companion object is initialized (i.e. before
 * Spring's `@DynamicPropertySource` reads its coordinates) and reused across
 * Spring contexts. We deliberately do not rely on the JUnit Testcontainers
 * extension lifecycle, which does not manage container fields declared in the
 * companion object of an abstract base class. Its coordinates are injected into
 * the Spring `Environment` through `@DynamicPropertySource`, so every
 * `@SpringBootTest` subclass runs Flyway V1..V7 against a real PostgreSQL 16
 * instance.
 *
 * The same `@DynamicPropertySource` registers the other properties every
 * integration test needs, so no subclass has to declare its own
 * `@SpringBootTest(properties = [...])` (which would split the context cache):
 *   - a shared oracle definitions root, so the ORACLES tests see the `smoke`
 *     definition without a per-class temp directory;
 *   - the shared Fake IdP secret the artifact-admin tests sign JWTs with;
 *   - `server.forward-headers-strategy=framework`, so an HTTP test can simulate
 *     a non-loopback caller with `X-Forwarded-For` and exercise the fail-closed
 *     401 path while loopback-dev stays enabled for the rest of the suite.
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
@SpringBootTest(
    webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT,
    classes = [FactoryServiceApplication::class, SharedIntegrationTestConfiguration::class],
)
abstract class PostgresContainerSpec {

    /** Concrete container type so the self-typed DSL methods chain cleanly in Kotlin. */
    class FactoryPostgresContainer : PostgreSQLContainer<FactoryPostgresContainer>("postgres:16-alpine")

    companion object {
        /** Fake IdP secret shared by every integration test. */
        private const val SHARED_FAKE_IDP_SECRET = "artifact-admin-test-secret"

        private val SMOKE_DEFINITION = """
            {
              "schemaVersion": "1",
              "id": "smoke",
              "version": "1.0.0",
              "domain": "factory",
              "argv": ["node", "script.mjs"],
              "cwd": "repo-root",
              "timeoutMs": 10000,
              "success": { "rule": "exit-code", "requireWork": true },
              "applicable": { "workflowTypes": ["oracle-smoke"], "stepIds": ["verify-code"] }
            }
        """.trimIndent()

        @JvmStatic
        protected val postgres: FactoryPostgresContainer =
            FactoryPostgresContainer()
                .withDatabaseName("factory_test")
                .withUsername("factory")
                .withPassword("factory")
                .apply { start() }

        /**
         * Shared oracle catalogue root, created once per JVM and exposed to every
         * integration test through [registerSharedProperties].
         */
        @JvmStatic
        protected val oracleDefinitionsRoot: Path =
            Files.createTempDirectory("factory-oracle-definitions").also { root ->
                Files.writeString(root.resolve("smoke@1.0.0.json"), SMOKE_DEFINITION)
            }

        /**
         * Empty plug-in drop-in directory shared by every integration test, so the
         * suite always runs the core *without* any deployed plugin regardless of
         * what is present in the default `plugins/` directory on disk.
         */
        @JvmStatic
        protected val pluginsDir: Path =
            Files.createTempDirectory("factory-test-plugins")

        @JvmStatic
        @DynamicPropertySource
        fun registerSharedProperties(registry: DynamicPropertyRegistry) {
            registry.add("spring.datasource.url", postgres::getJdbcUrl)
            registry.add("spring.datasource.username", postgres::getUsername)
            registry.add("spring.datasource.password", postgres::getPassword)
            registry.add("spring.datasource.driver-class-name") { "org.postgresql.Driver" }
            registry.add("spring.flyway.enabled") { "true" }
            registry.add("spring.flyway.validate-on-migrate") { "true" }
            registry.add("factory.oracle.definitions-root") { oracleDefinitionsRoot.toAbsolutePath().toString() }
            registry.add("factory.security.fake-idp-secret") { SHARED_FAKE_IDP_SECRET }
            registry.add("factory.plugins.dir") { pluginsDir.toAbsolutePath().toString() }
            registry.add("server.forward-headers-strategy") { "framework" }
        }
    }
}

/**
 * Integration-test-only beans shared by the single Spring context every
 * `factory-service` integration test uses.
 *
 * It is listed explicitly in [PostgresContainerSpec]'s `@SpringBootTest(classes = ...)`
 * so it is part of the one canonical context instead of being pulled in per test
 * class with `@Import`, which would risk a different context cache key.
 *
 * `OracleExecutionService` consumes [OracleArtifactPublisher] optionally; this
 * bean makes the "publish the linked artifact" branch observable in tests.
 */
@TestConfiguration
class SharedIntegrationTestConfiguration {

    @Bean
    fun oracleArtifactPublisher(jdbcTemplate: JdbcTemplate): OracleArtifactPublisher =
        object : OracleArtifactPublisher {
            override fun publishArtifact(
                scope: TenantScope,
                namespaceId: String,
                workflowId: String,
                artifactId: String,
            ) {
                jdbcTemplate.update(
                    """
                    UPDATE artifacts
                       SET availability_status = 'available'
                     WHERE organization_id = ?
                       AND workstream_id = ?
                       AND namespace_id = ?
                       AND workflow_id = ?
                       AND artifact_id = ?
                    """.trimIndent(),
                    scope.organizationId,
                    scope.workstreamId,
                    namespaceId,
                    workflowId,
                    artifactId,
                )
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
