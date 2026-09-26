package io.whozoss.factory.environment

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
import org.springframework.http.ResponseEntity

/**
 * HTTP integration tests of
 * [io.whozoss.factory.environment.web.WorkUnitEnvironmentController] against a
 * real PostgreSQL instance.
 *
 * Exercises `/api/factory/workflows/{workflowId}/environment` (GET, provision,
 * reconcile, release), asserting the canonical `{ "data": ... }` success
 * envelope and the `{ "error": { code, ... } }` failure envelope of the Node
 * control plane. The trust context is the loopback-dev principal.
 */
class EnvironmentLifecycleControllerTest : DomainIntegrationTest() {

    @Autowired
    private lateinit var restTemplate: TestRestTemplate

    @Test
    fun `provision returns 201 with a ready environment in a data envelope`() {
        val response = post("wf-http-provision", "/provision", provisionBody("wu-1"))

        assertThat(response.statusCode).isEqualTo(HttpStatus.CREATED)
        val data = data(response.body)
        assertThat(data).isNotNull
        assertThat(data!!["revision"]).isEqualTo(2)
        assertThat(environment(data)["lifecycleState"]).isEqualTo("ready")
        assertThat(environment(data)["environmentId"]).isEqualTo("wf-http-provision-wu-1")
        assertThat(environment(data)["worktreePath"]).isNotNull
        assertThat((data["fileAccess"] as Map<String, Any?>)["status"]).isEqualTo("bound")
    }

    @Test
    fun `repeated provision is idempotent and returns 200`() {
        val first = post("wf-http-idem", "/provision", provisionBody("wu-1"))
        val second = post("wf-http-idem", "/provision", provisionBody("wu-1"))

        assertThat(first.statusCode).isEqualTo(HttpStatus.CREATED)
        assertThat(second.statusCode).isEqualTo(HttpStatus.OK)
        assertThat(environment(data(second.body)!!)["environmentId"])
            .isEqualTo(environment(data(first.body)!!)["environmentId"])
    }

    @Test
    fun `get returns the current environment and reconcile reports it as owned`() {
        post("wf-http-get", "/provision", provisionBody("wu-1"))

        val get = restTemplate.exchange(
            "/api/factory/workflows/wf-http-get/environment",
            HttpMethod.GET,
            HttpEntity<Void>(headers()),
            jsonType(),
        )
        assertThat(get.statusCode).isEqualTo(HttpStatus.OK)
        assertThat(environment(data(get.body)!!)["lifecycleState"]).isEqualTo("ready")

        val reconcile = post("wf-http-get", "/reconcile", null)
        assertThat(reconcile.statusCode).isEqualTo(HttpStatus.OK)
        val reconciliation = data(reconcile.body)!!["reconciliation"] as Map<String, Any?>
        assertThat(reconciliation["status"]).isEqualTo("owned")
    }

    @Test
    fun `release decommissions the environment`() {
        post("wf-http-release", "/provision", provisionBody("wu-1"))

        val released = post("wf-http-release", "/release", """{"state":"decommissioned"}""")

        assertThat(released.statusCode).isEqualTo(HttpStatus.OK)
        val data = data(released.body)!!
        assertThat(environment(data)["lifecycleState"]).isEqualTo("decommissioned")
        assertThat((data["fileAccess"] as Map<String, Any?>)["status"]).isEqualTo("blocked")
    }

    @Test
    fun `get on an unknown workflow yields 404 ENVIRONMENT_NOT_FOUND`() {
        val response = restTemplate.exchange(
            "/api/factory/workflows/wf-unknown/environment",
            HttpMethod.GET,
            HttpEntity<Void>(headers()),
            jsonType(),
        )

        assertThat(response.statusCode).isEqualTo(HttpStatus.NOT_FOUND)
        assertThat(errorCode(response.body)).isEqualTo("ENVIRONMENT_NOT_FOUND")
    }

    @Test
    fun `provision without a branch yields 400 INVALID_ENVIRONMENT_REQUEST`() {
        val response = post("wf-http-invalid", "/provision", """{"workUnitId":"wu-1"}""")

        assertThat(response.statusCode).isEqualTo(HttpStatus.BAD_REQUEST)
        assertThat(errorCode(response.body)).isEqualTo("INVALID_ENVIRONMENT_REQUEST")
    }

    @Test
    fun `release with an unsupported state yields 400 INVALID_ENVIRONMENT_REQUEST`() {
        post("wf-http-bad-release", "/provision", provisionBody("wu-1"))

        val response = post("wf-http-bad-release", "/release", """{"state":"exploded"}""")

        assertThat(response.statusCode).isEqualTo(HttpStatus.BAD_REQUEST)
        assertThat(errorCode(response.body)).isEqualTo("INVALID_ENVIRONMENT_REQUEST")
    }

    private fun post(workflowId: String, suffix: String, body: String?): ResponseEntity<Map<String, Any?>> =
        restTemplate.exchange(
            "/api/factory/workflows/$workflowId/environment$suffix",
            HttpMethod.POST,
            HttpEntity(body, headers()),
            jsonType(),
        )

    private fun headers(): HttpHeaders = HttpHeaders().apply {
        contentType = MediaType.APPLICATION_JSON
        set("x-factory-namespace-id", "ns-http")
        set("x-factory-case-id", "case-http")
        set("x-factory-actor-id", "tester")
    }

    private fun jsonType(): ParameterizedTypeReference<Map<String, Any?>> =
        object : ParameterizedTypeReference<Map<String, Any?>>() {}

    private fun provisionBody(workUnitId: String): String =
        """{"workUnitId":"$workUnitId","integrationBranch":"main","branch":"feature/http"}"""

    @Suppress("UNCHECKED_CAST")
    private fun data(body: Map<String, Any?>?): Map<String, Any?>? = body?.get("data") as? Map<String, Any?>

    @Suppress("UNCHECKED_CAST")
    private fun environment(data: Map<String, Any?>): Map<String, Any?> = data["environment"] as Map<String, Any?>

    @Suppress("UNCHECKED_CAST")
    private fun errorCode(body: Map<String, Any?>?): String? =
        (body?.get("error") as? Map<String, Any?>)?.get("code") as? String
}
