package io.whozoss.agentos.persistence.neo4j

import org.springframework.boot.test.context.SpringBootTest
import org.springframework.test.context.ActiveProfiles
import org.springframework.test.context.DynamicPropertyRegistry
import org.springframework.test.context.DynamicPropertySource

/**
 * Runs the [AbstractCasePersistenceSpec] contract against a real Neo4j server
 * managed by Testcontainers (`neo4j` persistence mode).
 */
@SpringBootTest
@ActiveProfiles("test", "neo4j")
class Neo4jCasePersistenceSpec : AbstractCasePersistenceSpec() {
    companion object {
        @JvmStatic
        @DynamicPropertySource
        fun registerNeo4jProperties(registry: DynamicPropertyRegistry) =
            Neo4jContainerSpec.registerProperties(registry)
    }
}
