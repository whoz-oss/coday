package io.whozoss.factory.oracle

import io.whozoss.factory.Neo4jIntegrationTest
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
import org.springframework.test.context.TestPropertySource

/**
 * Fail-closed HTTP boundary test: with authentication enabled
 * (`factory.security.insecure-mode=false`) and no credential, a guarded route must be
 * refused by the authentication filter before any domain work.
 *
 * The per-class property override gives this class its own Spring context, while
 * the shared integration context keeps running in insecure mode.
 */
@TestPropertySource(properties = ["factory.security.insecure-mode=false"])
class OracleControllerAuthenticationTest : Neo4jIntegrationTest() {

    @Autowired
    private lateinit var restTemplate: TestRestTemplate

    @Suppress("UNCHECKED_CAST")
    private fun errorCode(body: Map<String, Any?>?): String? =
        (body?.get("error") as? Map<String, Any?>)?.get("code") as? String

    @Test
    fun `anonymous caller is refused with 401`() {
        val headers = HttpHeaders()
        headers.contentType = MediaType.APPLICATION_JSON
        val response = restTemplate.exchange(
            "/api/factory/workflows/wf/steps/step/oracles/smoke/runs",
            HttpMethod.POST,
            HttpEntity("""{"namespaceId":"ns"}""", headers),
            object : ParameterizedTypeReference<Map<String, Any?>>() {},
        )

        assertThat(response.statusCode).isEqualTo(HttpStatus.UNAUTHORIZED)
        assertThat(errorCode(response.body)).isEqualTo("UNAUTHENTICATED")
    }

    /**
     * The 401 of an unauthenticated SSE open must be a regular `application/json` error envelope,
     * not an event stream: the filter answers it before the `produces = text/event-stream`
     * endpoint is reached, so no converter has to write an `ErrorResponse` as an event stream.
     */
    @Test
    fun `an unauthenticated stream open is a json 401 and registers no connection`() {
        val headers = HttpHeaders().apply { accept = listOf(MediaType.TEXT_EVENT_STREAM) }

        val response = restTemplate.exchange(
            "/api/factory/workflows/stream",
            HttpMethod.GET,
            HttpEntity<Void>(headers),
            String::class.java,
        )

        assertThat(response.statusCode).isEqualTo(HttpStatus.UNAUTHORIZED)
        assertThat(response.headers.contentType?.toString().orEmpty()).contains("application/json")
        assertThat(response.body).contains("UNAUTHENTICATED").contains("\"error\"")
    }
}
