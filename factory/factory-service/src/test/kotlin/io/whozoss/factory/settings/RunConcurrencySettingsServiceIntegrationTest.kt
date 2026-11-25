package io.whozoss.factory.settings

import io.whozoss.factory.Neo4jDomainIntegrationTest
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.data.neo4j.core.Neo4jClient

/** Embedded-Neo4j integration tests of [RunConcurrencySettingsService]. */
class RunConcurrencySettingsServiceIntegrationTest : Neo4jDomainIntegrationTest() {

    @Autowired
    private lateinit var neo4jClient: Neo4jClient

    private fun service(default: Int = 5) = RunConcurrencySettingsService(neo4jClient, default)

    @Test
    fun `the default is 5 when nothing is persisted`() {
        val settings = service().settings()

        assertThat(settings.maxParallelRuns).isEqualTo(5)
        assertThat(settings.defaultMaxParallelRuns).isEqualTo(5)
        assertThat(settings.min).isEqualTo(1)
        assertThat(settings.max).isEqualTo(20)
        assertThat(settings.updatedAt).isNull()
        assertThat(settings.updatedBy).isNull()
    }

    @Test
    fun `an update is persisted and read back by a new instance`() {
        val updated = service().update(7, "alice")

        assertThat(updated.maxParallelRuns).isEqualTo(7)
        assertThat(updated.updatedBy).isEqualTo("alice")
        assertThat(updated.updatedAt).isNotNull()
        // A new service instance simulates a Factory restart.
        val restarted = service().settings()
        assertThat(restarted.maxParallelRuns).isEqualTo(7)
        assertThat(restarted.updatedBy).isEqualTo("alice")
        assertThat(restarted.updatedAt).isEqualTo(updated.updatedAt)
        assertThat(restarted.defaultMaxParallelRuns).isEqualTo(5)
    }

    @Test
    fun `the hot path reflects an update without a restart`() {
        val service = service()
        assertThat(service.maxParallelRuns()).isEqualTo(5)

        service.update(1, "alice")

        assertThat(service.maxParallelRuns()).isEqualTo(1)
        assertThat(service.current()).isEqualTo(1)
    }

    @Test
    fun `a value below 1 or above 20 is rejected and keeps the previous value`() {
        val service = service()
        service.update(7, "alice")

        listOf(0, -3, 21, 1000).forEach { invalid ->
            val error = runCatching { service.update(invalid, "bob") }.exceptionOrNull()
            assertThat(error).describedAs("value $invalid").isInstanceOf(InvalidRunConcurrencyException::class.java)
            error as InvalidRunConcurrencyException
            assertThat(error.statusCode).isEqualTo(400)
            assertThat(error.errorCode).isEqualTo("INVALID_MAX_PARALLEL_RUNS")
        }

        assertThat(service.maxParallelRuns()).isEqualTo(7)
        assertThat(service().maxParallelRuns()).isEqualTo(7)
    }

    @Test
    fun `the bounds 1 and 20 are accepted`() {
        val service = service()

        assertThat(service.update(1, "alice").maxParallelRuns).isEqualTo(1)
        assertThat(service.update(20, "alice").maxParallelRuns).isEqualTo(20)
    }

    @Test
    fun `an out of range configured default is clamped`() {
        assertThat(service(default = 0).settings().maxParallelRuns).isEqualTo(1)
        assertThat(service(default = 500).settings().maxParallelRuns).isEqualTo(20)
        assertThat(service(default = 500).settings().defaultMaxParallelRuns).isEqualTo(20)
    }
}
