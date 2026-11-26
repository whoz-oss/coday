package io.whozoss.factory.settings

import io.whozoss.factory.Neo4jDomainIntegrationTest
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.boot.test.web.client.TestRestTemplate
import org.springframework.core.ParameterizedTypeReference
import org.springframework.http.HttpEntity
import org.springframework.http.HttpHeaders
import org.springframework.http.HttpMethod
import org.springframework.http.MediaType
import org.springframework.http.ResponseEntity

/** HTTP tests of `/api/factory/admin/settings/run-concurrency` through the real filter chain. */
class RunConcurrencyAdminControllerHttpTest : Neo4jDomainIntegrationTest() {

    @Autowired
    private lateinit var restTemplate: TestRestTemplate

    @Autowired
    private lateinit var service: RunConcurrencySettingsService

    private val url = "/api/factory/admin/settings/run-concurrency"

    @BeforeEach
    fun resetSharedSetting() {
        // The service is a singleton of the shared context and caches its value across tests.
        service.update(5, "reset")
    }

    @Test
    fun `GET returns the effective value, its default and its range`() {
        val response = get()

        assertThat(response.statusCode.value()).isEqualTo(200)
        val data = data(response)
        assertThat(data["maxParallelRuns"]).isEqualTo(5)
        assertThat(data["defaultMaxParallelRuns"]).isEqualTo(5)
        assertThat(data["min"]).isEqualTo(1)
        assertThat(data["max"]).isEqualTo(20)
        assertThat(data).containsKeys("updatedAt", "updatedBy")
    }

    @Test
    fun `PUT applies the value and a following GET reflects it`() {
        val response = put("""{"maxParallelRuns": 7}""")

        assertThat(response.statusCode.value()).isEqualTo(200)
        assertThat(data(response)["maxParallelRuns"]).isEqualTo(7)
        assertThat(data(response)["updatedBy"]).isEqualTo(TEST_USER_ID)
        assertThat(data(response)["updatedAt"]).isNotNull()
        assertThat(data(get())["maxParallelRuns"]).isEqualTo(7)
        assertThat(service.maxParallelRuns()).isEqualTo(7)
    }

    @Test
    fun `PUT rejects out of range, non integer and missing values with INVALID_MAX_PARALLEL_RUNS`() {
        put("""{"maxParallelRuns": 7}""")
        listOf(
            """{"maxParallelRuns": 0}""",
            """{"maxParallelRuns": 21}""",
            """{"maxParallelRuns": 2.5}""",
            """{"maxParallelRuns": "5"}""",
            """{"maxParallelRuns": true}""",
            """{"maxParallelRuns": null}""",
            """{"maxParallelRuns": 99999999999}""",
            """{}""",
        ).forEach { body ->
            val response = put(body)
            assertThat(response.statusCode.value()).describedAs(body).isEqualTo(400)
            val error = response.body!!["error"] as Map<*, *>
            assertThat(error["code"]).describedAs(body).isEqualTo("INVALID_MAX_PARALLEL_RUNS")
            assertThat(error["details"]).describedAs(body).isEqualTo(mapOf("min" to 1, "max" to 20))
        }
        // The previous value is kept.
        assertThat(data(get())["maxParallelRuns"]).isEqualTo(7)
    }

    private fun get(): ResponseEntity<Map<String, Any?>> =
        restTemplate.exchange(url, HttpMethod.GET, HttpEntity<Void>(HttpHeaders()), mapType())

    private fun put(json: String): ResponseEntity<Map<String, Any?>> {
        val headers = HttpHeaders().apply { contentType = MediaType.APPLICATION_JSON }
        return restTemplate.exchange(url, HttpMethod.PUT, HttpEntity(json, headers), mapType())
    }

    private fun mapType(): ParameterizedTypeReference<Map<String, Any?>> =
        object : ParameterizedTypeReference<Map<String, Any?>>() {}

    @Suppress("UNCHECKED_CAST")
    private fun data(response: ResponseEntity<Map<String, Any?>>): Map<String, Any?> =
        response.body!!["data"] as Map<String, Any?>
}
