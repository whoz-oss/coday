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
import org.springframework.http.ResponseEntity

/**
 * HTTP integration tests of [io.whozoss.factory.oracle.web.OracleController]
 * against the embedded Neo4j engine (in-process harness, no Docker).
 *
 * The trust context is the loopback-dev principal granted by
 * `LocalDevMembershipResolver` (`org-local-dev` / `ws-default`).
 */
class OracleControllerIntegrationTest : Neo4jIntegrationTest() {

    @Autowired
    private lateinit var restTemplate: TestRestTemplate

    private fun postRun(
        oracleId: String,
        body: String,
        idempotencyKey: String? = null,
    ): ResponseEntity<Map<String, Any?>> {
        val headers = HttpHeaders()
        headers.contentType = MediaType.APPLICATION_JSON
        if (idempotencyKey != null) headers.set("Idempotency-Key", idempotencyKey)
        val url = "/api/factory/workflows/$WORKFLOW/steps/verify-code/oracles/$oracleId/runs"
        return restTemplate.exchange(
            url,
            HttpMethod.POST,
            HttpEntity(body, headers),
            object : ParameterizedTypeReference<Map<String, Any?>>() {},
        )
    }

    @Suppress("UNCHECKED_CAST")
    private fun data(body: Map<String, Any?>?): Map<String, Any?>? = body?.get("data") as? Map<String, Any?>

    @Suppress("UNCHECKED_CAST")
    private fun errorCode(body: Map<String, Any?>?): String? =
        (body?.get("error") as? Map<String, Any?>)?.get("code") as? String

    @Test
    fun `runs an oracle and wraps the payload in a data envelope`() {
        val response = postRun("smoke", """{"namespaceId":"$NAMESPACE"}""")

        assertThat(response.statusCode).isEqualTo(HttpStatus.CREATED)
        val data = data(response.body)
        assertThat(data).isNotNull
        assertThat(data!!["workflowId"]).isEqualTo(WORKFLOW)
        assertThat(data["stepId"]).isEqualTo("verify-code")
        assertThat(data["oracleId"]).isEqualTo("smoke")
        assertThat(data["status"]).isEqualTo("SUCCEEDED")
        assertThat(data["revision"]).isEqualTo(2)
        assertThat(data["executionId"]).isNotNull
    }

    @Test
    fun `unknown oracle yields 404 ORACLE_NOT_FOUND`() {
        val response = postRun("does-not-exist", """{"namespaceId":"$NAMESPACE"}""")

        assertThat(response.statusCode).isEqualTo(HttpStatus.NOT_FOUND)
        assertThat(errorCode(response.body)).isEqualTo("ORACLE_NOT_FOUND")
    }

    @Test
    fun `missing namespaceId yields 400 INVALID_ORACLE_RUN_REQUEST`() {
        val response = postRun("smoke", """{}""")

        assertThat(response.statusCode).isEqualTo(HttpStatus.BAD_REQUEST)
        assertThat(errorCode(response.body)).isEqualTo("INVALID_ORACLE_RUN_REQUEST")
    }

    @Test
    fun `idempotency key replays the same execution with 200`() {
        val first = postRun("smoke", """{"namespaceId":"$NAMESPACE"}""", idempotencyKey = "controller-key-1")
        assertThat(first.statusCode).isEqualTo(HttpStatus.CREATED)
        val firstExecutionId = data(first.body)!!["executionId"]

        val second = postRun("smoke", """{"namespaceId":"$NAMESPACE"}""", idempotencyKey = "controller-key-1")
        assertThat(second.statusCode).isEqualTo(HttpStatus.OK)
        val secondData = data(second.body)!!
        assertThat(secondData["executionId"]).isEqualTo(firstExecutionId)
        assertThat(secondData["idempotent"]).isEqualTo(true)
    }

    companion object {
        private const val ORG = "org-local-dev"
        private const val WS = "ws-default"
        private const val NAMESPACE = "ns-controller-test"
        private const val WORKFLOW = "wf-controller-test"
    }
}
