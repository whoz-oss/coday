package io.whozoss.agentos.persistence.neo4j

import io.whozoss.agentos.persistence.neo4j.Neo4jContainerSpec.registerProperties
import org.springframework.test.context.DynamicPropertyRegistry
import org.testcontainers.containers.Neo4jContainer
import org.testcontainers.utility.DockerImageName

/**
 * Shared Testcontainers setup for all Neo4j persistence specs.
 *
 * Starts a single [Neo4jContainer] once per JVM run (lazy singleton) and
 * exposes [registerProperties] for use in [@DynamicPropertySource] methods.
 *
 * Concrete specs delegate to this object from their companion:
 * ```kotlin
 * companion object {
 *     @JvmStatic
 *     @DynamicPropertySource
 *     fun registerNeo4jProperties(registry: DynamicPropertyRegistry) =
 *         Neo4jContainerSpec.registerProperties(registry)
 * }
 * ```
 */
object Neo4jContainerSpec {
    val container: Neo4jContainer<*> by lazy {
        Neo4jContainer(DockerImageName.parse(Neo4jContainerSupport.NEO4J_IMAGE))
            .withoutAuthentication()
            .also { it.start() }
    }

    fun registerProperties(registry: DynamicPropertyRegistry) {
        registry.add("spring.neo4j.uri") { container.boltUrl }
        registry.add("spring.neo4j.authentication.username") { "neo4j" }
        registry.add("spring.neo4j.authentication.password") { "" }
    }
}
