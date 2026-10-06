package io.whozoss.factory

import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.boot.test.web.client.TestRestTemplate
import org.springframework.context.ApplicationContext
import org.springframework.http.HttpStatus

/**
 * Boot-level smoke test of the factory-service application context.
 *
 * Exercises the in-process Neo4j harness through [Neo4jIntegrationTest]:
 * the Spring context starts, the graph is reachable and the
 * `Neo4jSchemaInitializer` has applied its constraints. Flyway and the retired
 * PostgreSQL schema are no longer part of the boot path.
 */
class FactoryServiceApplicationIntegrationTest : Neo4jIntegrationTest() {

    @Autowired
    private lateinit var applicationContext: ApplicationContext

    @Autowired
    private lateinit var restTemplate: TestRestTemplate

    @Test
    fun `spring context loads with a live neo4j driver`() {
        assertThat(applicationContext).isNotNull
        assertThat(neo4jDriver).isNotNull
    }

    @Test
    fun `neo4j graph is reachable and queryable`() {
        neo4jDriver.session().use { session ->
            val count = session.run("MATCH (n) RETURN count(n) AS c").single().get("c").asLong()
            assertThat(count).isGreaterThanOrEqualTo(0L)
        }
    }

    @Test
    fun `neo4j schema constraints are initialised at boot`() {
        neo4jDriver.session().use { session ->
            val names = session.run("SHOW CONSTRAINTS").list { it.get("name").asString() }
            assertThat(names).contains(
                "oracle_execution_id_unique",
                "artifact_metadata_id_unique",
                "workflow_instance_id_unique",
                "work_unit_lease_id_unique",
                "workstream_id_unique",
            )
        }
    }

    @Test
    fun `actuator health returns UP`() {
        val response = restTemplate.getForEntity("/actuator/health", Map::class.java)
        assertThat(response.statusCode).isEqualTo(HttpStatus.OK)
        assertThat(response.body?.get("status")).isEqualTo("UP")
    }
}
