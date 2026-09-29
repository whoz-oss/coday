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
import java.nio.file.Files
import java.nio.file.Path

/**
 * Legacy PostgreSQL integration fixture, retired by the Postgres → embedded Neo4j
 * swap (Phase 1).
 *
 * The repositories not yet migrated to Neo4j (workflow, lease, delivery,
 * work-unit, agent-attempt, …) still run on the interim H2 datasource, but their
 * integration tests assert on PostgreSQL-specific SQL (JSONB operators,
 * `ON CONFLICT`, `information_schema`) and on the Flyway-created schema, none of
 * which exist anymore. Rather than delete coverage, the suite is skipped unless
 * an operator explicitly opts in by setting the
 * `FACTORY_RUN_LEGACY_RELATIONAL_TESTS=true` environment variable. Phase 2
 * migrates these aggregates to Neo4j and replaces this fixture with
 * [Neo4jIntegrationTest].
 *
 * All subclasses share the same `MergedContextConfiguration`, so Spring Test
 * would cache a single context — but the opt-in condition means it is normally
 * never started.
 */
@ExtendWith(LegacyRelationalEnabledCondition::class)
@SpringBootTest(
    webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT,
    classes = [FactoryServiceApplication::class, SharedIntegrationTestConfiguration::class],
)
abstract class PostgresContainerSpec {

    companion object {
        /** Fake IdP secret shared by every integration test. */
        private const val SHARED_FAKE_IDP_SECRET = "artifact-admin-test-secret"

        /**
         * Shared oracle catalogue root, created once per JVM and exposed to every
         * integration test through [registerSharedProperties].
         */
        @JvmStatic
        protected val oracleDefinitionsRoot: Path =
            Files.createTempDirectory("factory-oracle-definitions")

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
            registry.add("factory.oracle.definitions-root") { oracleDefinitionsRoot.toAbsolutePath().toString() }
            registry.add("factory.security.fake-idp-secret") { SHARED_FAKE_IDP_SECRET }
            registry.add("factory.plugins.dir") { pluginsDir.toAbsolutePath().toString() }
            // The outbox drain worker must never race test fixtures.
            registry.add("factory.outbox.drain-enabled") { "false" }
            registry.add("server.forward-headers-strategy") { "framework" }
        }
    }
}

/**
 * Integration-test-only beans shared by the single Spring context every
 * `factory-service` integration test uses.
 *
 * `OracleExecutionService` consumes [OracleArtifactPublisher] optionally; this
 * bean makes the "publish the linked artifact" branch observable in the legacy
 * relational tests.
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
 * JUnit [ExecutionCondition] that keeps the retired PostgreSQL integration tests
 * disabled unless an operator explicitly opts in.
 *
 * The PostgreSQL driver and the Flyway migrations were removed in Phase 1, so the
 * legacy fixtures cannot run as-is; they are retained until Phase 2 migrates the
 * remaining aggregates to Neo4j.
 */
class LegacyRelationalEnabledCondition : ExecutionCondition {
    override fun evaluateExecutionCondition(context: ExtensionContext): ConditionEvaluationResult =
        if (System.getenv("FACTORY_RUN_LEGACY_RELATIONAL_TESTS") == "true") {
            ConditionEvaluationResult.enabled("Legacy relational integration tests explicitly enabled")
        } else {
            ConditionEvaluationResult.disabled(
                "Legacy PostgreSQL integration tests retired by the Neo4j swap (Phase 1); " +
                    "set FACTORY_RUN_LEGACY_RELATIONAL_TESTS=true to run them",
            )
        }
}
