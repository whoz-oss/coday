package io.whozoss.agentos.persistence.neo4j

import io.kotest.core.annotation.EnabledIf
import org.springframework.boot.test.context.SpringBootTest
import org.springframework.test.context.ActiveProfiles
import org.springframework.test.context.DynamicPropertyRegistry
import org.springframework.test.context.DynamicPropertySource

/**
 * Runs the [AbstractAiModelPersistenceSpec] contract against a real Neo4j server
 * started in Docker via Testcontainers.
 *
 * Skipped automatically when Docker is unavailable.
 */
@SpringBootTest
@ActiveProfiles("test", "neo4j")
@EnabledIf(DockerAvailableCondition::class)
class Neo4jAiModelPersistenceSpec : AbstractAiModelPersistenceSpec() {
    companion object {
        @JvmStatic
        @DynamicPropertySource
        fun registerNeo4jProperties(registry: DynamicPropertyRegistry) =
            Neo4jContainerSpec.registerProperties(registry)
    }
}
