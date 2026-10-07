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

/**
 * Fail-closed HTTP boundary test: with no credential, the oracle run endpoint
 * must refuse the caller before any domain work.
 *
 * The suite runs with `factory.security.allow-loopback-dev` enabled (the shared
 * integration configuration), so a genuine localhost request would be promoted
 * to the loopback-dev wildcard. To exercise the anonymous path without splitting
 * the Spring context with a per-class property override, the request carries an
 * `X-Forwarded-For` header: `server.forward-headers-strategy=framework` installs
 * the [org.springframework.web.filter.ForwardedHeaderFilter], which rewrites
 * `request.remoteAddr` to the forwarded (non-loopback) address.
 */
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
        // Force a non-loopback remote address so loopback-dev does not apply.
        headers.set("X-Forwarded-For", "203.0.113.195")
        val response = restTemplate.exchange(
            "/api/factory/workflows/wf/steps/step/oracles/smoke/runs",
            HttpMethod.POST,
            HttpEntity("""{"namespaceId":"ns"}""", headers),
            object : ParameterizedTypeReference<Map<String, Any?>>() {},
        )

        assertThat(response.statusCode).isEqualTo(HttpStatus.UNAUTHORIZED)
        assertThat(errorCode(response.body)).isEqualTo("UNAUTHENTICATED")
    }
}
