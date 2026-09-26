package io.whozoss.factory.oracle

import io.whozoss.factory.PostgresContainerSpec
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.boot.test.context.SpringBootTest
import org.springframework.boot.test.web.client.TestRestTemplate
import org.springframework.core.ParameterizedTypeReference
import org.springframework.http.HttpEntity
import org.springframework.http.HttpHeaders
import org.springframework.http.HttpMethod
import org.springframework.http.HttpStatus
import org.springframework.http.MediaType
import org.testcontainers.junit.jupiter.Testcontainers

/**
 * Fail-closed HTTP boundary test: with loopback-dev disabled and no credential,
 * the oracle run endpoint must refuse the caller before any domain work.
 */
@SpringBootTest(
    webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT,
    properties = ["factory.security.allow-loopback-dev=false"],
)
@Testcontainers(disabledWithoutDocker = true)
class OracleControllerAuthenticationTest : PostgresContainerSpec() {

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
}
