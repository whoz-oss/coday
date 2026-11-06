package io.whozoss.agentos.persistence.neo4j

import org.springframework.boot.test.context.SpringBootTest
import org.springframework.test.context.ActiveProfiles
import org.springframework.test.context.DynamicPropertyRegistry
import org.springframework.test.context.DynamicPropertySource

/**
 * Runs the [AbstractAiModelAdvancedExecutionMigrationSpec] contract against a real Neo4j server
 * started in Docker via Testcontainers. Skipped automatically when Docker is unavailable.
 */
@SpringBootTest
@ActiveProfiles("test", "neo4j")
class Neo4jAiModelAdvancedExecutionMigrationSpec : AbstractAiModelAdvancedExecutionMigrationSpec() {
    companion object {
        @JvmStatic
        @DynamicPropertySource
        fun registerNeo4jProperties(registry: DynamicPropertyRegistry) = Neo4jContainerSpec.registerProperties(registry)
    }
}
