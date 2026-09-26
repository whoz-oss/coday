package io.whozoss.factory

import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.boot.test.context.SpringBootTest
import org.springframework.boot.test.web.client.TestRestTemplate
import org.springframework.http.HttpStatus
import org.springframework.jdbc.core.JdbcTemplate
import org.testcontainers.junit.jupiter.Testcontainers

@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
@Testcontainers(disabledWithoutDocker = true)
class FactoryServiceApplicationIntegrationTest : PostgresContainerSpec() {

    @Autowired
    private lateinit var jdbcTemplate: JdbcTemplate

    @Autowired
    private lateinit var restTemplate: TestRestTemplate

    @Test
    fun `spring context loads`() {
        assertThat(jdbcTemplate).isNotNull
    }

    @Test
    fun `flyway applies all migrations V1 through V7`() {
        val versions = jdbcTemplate.queryForList(
            "SELECT version FROM flyway_schema_history WHERE success = true ORDER BY installed_rank",
            String::class.java,
        )
        assertThat(versions).contains("1", "2", "3", "4", "5", "6", "7")
    }

    @Test
    fun `expected tables exist after migration`() {
        val tables = jdbcTemplate.queryForList(
            "SELECT table_name FROM information_schema.tables WHERE table_schema = 'public'",
            String::class.java,
        )
        assertThat(tables).contains(
            "workflow_instances",
            "artifacts",
            "work_unit_leases",
            "outbox_events",
            "workflow_definitions",
            "organizations",
            "organization_memberships",
        )
    }

    @Test
    fun `actuator health returns UP`() {
        val response = restTemplate.getForEntity("/actuator/health", Map::class.java)
        assertThat(response.statusCode).isEqualTo(HttpStatus.OK)
        assertThat(response.body?.get("status")).isEqualTo("UP")
    }
}
