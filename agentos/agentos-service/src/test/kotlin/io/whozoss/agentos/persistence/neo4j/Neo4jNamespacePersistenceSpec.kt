package io.whozoss.agentos.persistence.neo4j

import org.springframework.boot.test.context.SpringBootTest
import org.springframework.test.context.ActiveProfiles
import org.springframework.test.context.DynamicPropertyRegistry
import org.springframework.test.context.DynamicPropertySource
import org.testcontainers.containers.Neo4jContainer
import org.testcontainers.utility.DockerImageName

/**
 * Runs the [AbstractNamespacePersistenceSpec] contract against a real Neo4j server
 * managed by Testcontainers (`neo4j` persistence mode).
 *
 * The `neo4j` profile sets `agentos.persistence.mode=neo4j`, which activates
 * [io.whozoss.agentos.config.Neo4jPersistenceConfiguration] and wires the
 * Neo4j-backed repositories.
 */
@SpringBootTest
@ActiveProfiles("test", "neo4j")
class Neo4jNamespacePersistenceSpec : AbstractNamespacePersistenceSpec() {
    companion object {
        val neo4j: Neo4jContainer<*> =
            Neo4jContainer(DockerImageName.parse(Neo4jContainerSupport.NEO4J_IMAGE))
                .withoutAuthentication()
                .also { it.start() }

        @JvmStatic
        @DynamicPropertySource
        fun registerNeo4jProperties(registry: DynamicPropertyRegistry) {
            registry.add("spring.neo4j.uri") { neo4j.boltUrl }
            registry.add("spring.neo4j.authentication.username") { "neo4j" }
            registry.add("spring.neo4j.authentication.password") { "" }
        }
    }
}
