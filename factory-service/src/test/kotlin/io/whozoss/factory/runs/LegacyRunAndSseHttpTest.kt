package io.whozoss.factory.runs

import io.whozoss.factory.DomainIntegrationTest
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.boot.test.web.client.TestRestTemplate
import org.springframework.core.ParameterizedTypeReference
import org.springframework.http.HttpEntity
import org.springframework.http.HttpHeaders
import org.springframework.http.HttpMethod
import org.springframework.http.HttpStatus
import org.springframework.http.MediaType

/**
 * HTTP integration tests of the legacy runs REST surface and the SSE stream
 * framing (`tag: sse`) through the real servlet stack.
 */
class LegacyRunAndSseHttpTest : DomainIntegrationTest() {

    @Autowired
    private lateinit var restTemplate: TestRestTemplate

    private fun headers(withForward: Boolean = false): HttpHeaders = HttpHeaders().apply {
        if (withForward) add("X-Forwarded-For", "203.0.113.9")
    }

    @Test
    fun `the runs list is a json array`() {
        val response = restTemplate.exchange(
            "/api/runs",
            HttpMethod.GET,
            HttpEntity<Void>(headers()),
            object : ParameterizedTypeReference<List<Map<String, Any?>>>() {},
        )
        assertThat(response.statusCode).isEqualTo(HttpStatus.OK)
        assertThat(response.body).isNotNull
    }

    @Test
    fun `an unknown run detail yields 404 RUN_NOT_FOUND`() {
        val response = restTemplate.exchange(
            "/api/runs/does-not-exist",
            HttpMethod.GET,
            HttpEntity<Void>(headers()),
            Map::class.java,
        )
        assertThat(response.statusCode).isEqualTo(HttpStatus.NOT_FOUND)
        val error = response.body?.get("error") as? Map<*, *>
        assertThat(error?.get("code")).isEqualTo("RUN_NOT_FOUND")
    }

    @Test
    fun `an unknown review-gate yields 404 and the deprecated route yields 410`() {
        val gate = restTemplate.exchange(
            "/api/factory/runs/does-not-exist/review-gate",
            HttpMethod.GET,
            HttpEntity<Void>(headers()),
            Map::class.java,
        )
        assertThat(gate.statusCode).isEqualTo(HttpStatus.NOT_FOUND)

        val deprecated = restTemplate.exchange(
            "/api/review-gate",
            HttpMethod.GET,
            HttpEntity<Void>(headers()),
            Map::class.java,
        )
        assertThat(deprecated.statusCode).isEqualTo(HttpStatus.GONE)
        val error = deprecated.body?.get("error") as? Map<*, *>
        assertThat(error?.get("code")).isEqualTo("DEPRECATED_ROUTE")
    }

    @Test
    fun `the SSE stream emits connected and done frames for an unknown run`() {
        val response = restTemplate.exchange(
            "/api/runs/does-not-exist/stream",
            HttpMethod.GET,
            HttpEntity<Void>(headers().apply { accept = listOf(MediaType.TEXT_EVENT_STREAM) }),
            String::class.java,
        )
        assertThat(response.statusCode).isEqualTo(HttpStatus.OK)
        assertThat(response.headers.contentType?.toString().orEmpty()).contains("text/event-stream")
        assertThat(response.headers.getFirst("Cache-Control")).isEqualTo("no-cache")
        assertThat(response.body.orEmpty()).contains(": connected")
        assertThat(response.body.orEmpty()).contains("data: {\"done\":true}")
    }

    @Test
    fun `an SSE stream failure is rendered as a json error envelope`() {
        val response = restTemplate.exchange(
            "/api/factory/runs/does-not-exist/stream",
            HttpMethod.GET,
            HttpEntity<Void>(headers(withForward = true).apply { accept = listOf(MediaType.TEXT_EVENT_STREAM) }),
            String::class.java,
        )
        assertThat(response.statusCode).isEqualTo(HttpStatus.UNAUTHORIZED)
        assertThat(response.headers.contentType?.toString().orEmpty()).contains("application/json")
        assertThat(response.body.orEmpty()).contains("TRUST_CONTEXT_UNAVAILABLE")
    }
}
