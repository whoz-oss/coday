package io.whozoss.factory

import io.whozoss.factory.artifact.domain.ArtifactAvailabilityStatus
import io.whozoss.factory.artifact.infrastructure.persistence.SpringDataNeo4jArtifactRepository
import io.whozoss.factory.artifact.infrastructure.persistence.ArtifactMetadataNode
import io.whozoss.factory.oracle.publisher.OracleArtifactPublisher
import io.whozoss.factory.persistence.TenantScope
import io.whozoss.factory.persistence.neo4j.EmbeddedNeo4jTestConfiguration
import io.whozoss.factory.persistence.neo4j.Neo4jTestSupport
import org.junit.jupiter.api.BeforeEach
import org.neo4j.driver.Driver
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.boot.test.context.SpringBootTest
import org.springframework.boot.test.context.TestConfiguration
import org.springframework.context.annotation.Bean
import org.springframework.context.annotation.Import
import org.springframework.test.context.ActiveProfiles
import org.springframework.test.context.DynamicPropertyRegistry
import org.springframework.test.context.DynamicPropertySource
import java.nio.file.Files
import java.nio.file.Path
import java.time.Instant

/**
 * Shared Spring Boot integration fixture for the Neo4j-backed aggregates.
 *
 * Unlike the retired [PostgresContainerSpec], this fixture needs no Docker: the
 * Neo4j engine is an in-process harness ([EmbeddedNeo4jTestConfiguration]) and
 * the interim relational store is the in-memory H2 datasource declared in
 * `application.yml` / `application-test.yml`.
 *
 * All subclasses share the exact same `MergedContextConfiguration`, so Spring
 * Test caches ONE `ApplicationContext` for the whole group.
 */
@SpringBootTest(
    webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT,
    classes = [FactoryServiceApplication::class, SharedNeo4jIntegrationTestConfiguration::class],
)
@ActiveProfiles("test", "embedded-neo4j")
@Import(EmbeddedNeo4jTestConfiguration::class)
abstract class Neo4jIntegrationTest {

    @Autowired
    protected lateinit var neo4jDriver: Driver

    @BeforeEach
    fun clearGraph() {
        Neo4jTestSupport.clearDatabase(neo4jDriver)
    }

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

        /** Shared oracle catalogue root, created once per JVM. */
        @JvmStatic
        protected val oracleDefinitionsRoot: Path =
            Files.createTempDirectory("factory-oracle-definitions").also { root ->
                Files.writeString(root.resolve("smoke@1.0.0.json"), SMOKE_DEFINITION)
            }

        /** Empty plug-in drop-in directory shared by every integration test. */
        @JvmStatic
        protected val pluginsDir: Path =
            Files.createTempDirectory("factory-test-plugins")

        @JvmStatic
        @DynamicPropertySource
        fun registerSharedProperties(registry: DynamicPropertyRegistry) {
            registry.add("factory.oracle.definitions-root") { oracleDefinitionsRoot.toAbsolutePath().toString() }
            registry.add("factory.security.fake-idp-secret") { SHARED_FAKE_IDP_SECRET }
            registry.add("factory.plugins.dir") { pluginsDir.toAbsolutePath().toString() }
            registry.add("factory.outbox.drain-enabled") { "false" }
            registry.add("server.forward-headers-strategy") { "framework" }
        }
    }
}

/**
 * Integration-test-only beans shared by the Neo4j integration context.
 *
 * `OracleExecutionService` consumes [OracleArtifactPublisher] optionally; this
 * bean makes the "publish the linked artifact" branch observable in tests by
 * flipping the `:ArtifactMetadata` node's availability to `available`.
 */
@TestConfiguration
class SharedNeo4jIntegrationTestConfiguration {

    @Bean
    fun oracleArtifactPublisher(
        repository: SpringDataNeo4jArtifactRepository,
    ): OracleArtifactPublisher =
        object : OracleArtifactPublisher {
            override fun publishArtifact(
                scope: TenantScope,
                namespaceId: String,
                workflowId: String,
                artifactId: String,
            ) {
                val node: ArtifactMetadataNode = repository.findById(artifactId).orElse(null) ?: return
                if (node.organizationId != scope.organizationId || node.workstreamId != scope.workstreamId) return
                repository.save(
                    node.copy(
                        availabilityStatus = ArtifactAvailabilityStatus.AVAILABLE.wireValue,
                        updatedAt = Instant.now(),
                    ),
                )
            }
        }
}
