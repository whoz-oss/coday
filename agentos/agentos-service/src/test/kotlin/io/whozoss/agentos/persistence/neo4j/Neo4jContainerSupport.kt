package io.whozoss.agentos.persistence.neo4j

/**
 * Shared helpers for Neo4j integration tests.
 */
object Neo4jContainerSupport {
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
