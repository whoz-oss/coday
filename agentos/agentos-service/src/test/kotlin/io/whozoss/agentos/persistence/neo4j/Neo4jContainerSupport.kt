package io.whozoss.agentos.persistence.neo4j

/**
 * Shared helpers for Neo4j integration tests.
 */
object Neo4jContainerSupport {
    /**
     * Docker image tag for the Neo4j Testcontainers server.
     * Kept in sync with the embedded Neo4j engine version (neo4jEmbedded in libs.versions.toml)
     * so both persistence modes test against the same Neo4j release line.
     */
    const val NEO4J_IMAGE = "neo4j:2026.02-community"

    /**
     * Delete all nodes and relationships in the database.
     * Call in a beforeEach block to ensure test isolation.
     */
    fun clearDatabase(driver: org.neo4j.driver.Driver) {
        driver.session().use { session ->
            session.run("MATCH (n) DETACH DELETE n")
        }
    }
}
