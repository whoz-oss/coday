package io.whozoss.factory.openapi

import io.whozoss.factory.Neo4jDomainIntegrationTest
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.boot.test.web.client.TestRestTemplate
import org.springframework.core.io.ClassPathResource
import org.springframework.http.HttpMethod
import org.springframework.http.HttpStatus

class FactoryOpenApiControllerHttpTest : Neo4jDomainIntegrationTest() {

    @Autowired
    private lateinit var restTemplate: TestRestTemplate

    @Test
    fun `worker contract is served anonymously from its packaged source`() {
        assertContract(
            "/api/factory/openapi/factory-worker.yaml",
            "openapi/factory-worker-http-api-submit-step-result.yaml",
        )
    }

    @Test
    fun `workstream contract is served anonymously from its packaged source`() {
        assertContract(
            "/api/factory/openapi/factory-workstream.yaml",
            "openapi/factory-workstream-http-api.yaml",
        )
    }

    @Test
    fun `openapi exposure does not accept posts or unknown routes`() {
        val post = restTemplate.exchange(
            "/api/factory/openapi/factory-worker.yaml",
            HttpMethod.POST,
            null,
            String::class.java,
        )
        val unknown = restTemplate.getForEntity(
            "/api/factory/openapi/factory-openapi.yaml",
            String::class.java,
        )

        assertThat(post.statusCode).isEqualTo(HttpStatus.METHOD_NOT_ALLOWED)
        assertThat(unknown.statusCode).isEqualTo(HttpStatus.NOT_FOUND)
    }

    private fun assertContract(path: String, resourcePath: String) {
        val response = restTemplate.getForEntity(path, String::class.java)
        val expected = ClassPathResource(resourcePath).inputStream.bufferedReader(Charsets.UTF_8).use { it.readText() }

        assertThat(response.statusCode).isEqualTo(HttpStatus.OK)
        assertThat(response.headers.contentType.toString()).isEqualTo("application/yaml;charset=UTF-8")
        assertThat(response.headers.getFirst("X-Content-Type-Options")).isEqualTo("nosniff")
        assertThat(response.body).isEqualTo(expected)
    }
}
