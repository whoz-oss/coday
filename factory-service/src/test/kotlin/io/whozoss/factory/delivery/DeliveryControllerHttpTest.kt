package io.whozoss.factory.delivery

import io.whozoss.factory.DomainIntegrationTest
import io.whozoss.factory.environment.service.ProvisionEnvironmentCommand
import io.whozoss.factory.environment.service.WorkUnitEnvironmentService
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
 * HTTP integration tests of the delivery control plane
 * (`/api/factory/workflows/{workflowId}/delivery`) against a real PostgreSQL
 * instance.
 *
 * Exercises the trusted boundary (namespace/case identity from the trust
 * context headers), the canonical `{ "data": ... }` success envelope, the
 * `{ "error": { code, ... } }` failure envelope, delivery resolution against a
 * provisioned work environment, git checkpointing and evidence-gated promotion.
 */
class DeliveryControllerHttpTest : DomainIntegrationTest() {

    @Autowired
    private lateinit var restTemplate: TestRestTemplate

    @Autowired
    private lateinit var environmentService: WorkUnitEnvironmentService

    private fun provision(workflowId: String): Unit = environmentService.provision(
        scope,
        ProvisionEnvironmentCommand(
            workflowId = workflowId,
            workUnitId = "wu-1",
            namespaceId = NAMESPACE,
            parentCaseId = CASE,
            integrationBranch = "main",
            branch = "feature/http",
            createdBy = "tester",
        ),
    ).let { }

    @Test
    fun `get without namespace and case identity yields 401 TRUST_CONTEXT_UNAVAILABLE`() {
        val response = restTemplate.exchange(
            "/api/factory/workflows/wf-delivery-noctx/delivery",
            HttpMethod.GET,
            HttpEntity<Void>(HttpHeaders().apply { contentType = MediaType.APPLICATION_JSON }),
            jsonType(),
        )
        assertThat(response.statusCode).isEqualTo(HttpStatus.UNAUTHORIZED)
        assertThat(errorCode(response.body)).isEqualTo("TRUST_CONTEXT_UNAVAILABLE")
    }

    @Test
    fun `get resolves a provisioned environment and returns the delivery snapshot`() {
        provision("wf-delivery-get")
        val response = get("wf-delivery-get")
        assertThat(response.statusCode).isEqualTo(HttpStatus.OK)
        val data = data(response.body)!!
        assertThat(data["stage"]).isEqualTo("implementation-ready")
        assertThat(data["deliveryId"]).isEqualTo("wf-delivery-get-delivery")
        assertThat(data["revision"]).isEqualTo(1)
    }

    @Test
    fun `get on a workflow without an environment yields 409 DELIVERY_BINDING_UNAVAILABLE`() {
        val response = get("wf-delivery-missing")
        assertThat(response.statusCode).isEqualTo(HttpStatus.CONFLICT)
        assertThat(errorCode(response.body)).isEqualTo("DELIVERY_BINDING_UNAVAILABLE")
    }

    @Test
    fun `pull request without trusted configuration yields 422 PULL_REQUEST_NOT_CONFIGURED`() {
        provision("wf-delivery-pr")
        val response = post("wf-delivery-pr", "/pull-request", """{"title":"t"}""")
        assertThat(response.statusCode).isEqualTo(HttpStatus.UNPROCESSABLE_ENTITY)
        assertThat(errorCode(response.body)).isEqualTo("PULL_REQUEST_NOT_CONFIGURED")
    }

    @Test
    fun `evidence-gated promotion to artifact-ready succeeds`() {
        provision("wf-delivery-promote")
        val artifact = post(
            "wf-delivery-promote",
            "/evidence",
            """{"kind":"artifact","outcome":"pass","idempotencyKey":"ev-artifact-1","facts":{}}""",
        )
        val oracle = post(
            "wf-delivery-promote",
            "/evidence",
            """{"kind":"oracle-result","outcome":"pass","idempotencyKey":"ev-oracle-1","facts":{}}""",
        )
        assertThat(artifact.statusCode).isEqualTo(HttpStatus.CREATED)
        assertThat(oracle.statusCode).isEqualTo(HttpStatus.CREATED)
        val artifactId = data(artifact.body)!!["evidenceId"] as String
        val oracleId = data(oracle.body)!!["evidenceId"] as String

        val promote = post(
            "wf-delivery-promote",
            "/promote",
            """{"deliveryId":"wf-delivery-promote-delivery","expectedRevision":1,"requestedStage":"artifact-ready","evidenceIds":["$artifactId","$oracleId"],"idempotencyKey":"promote-1"}""",
        )
        assertThat(promote.statusCode).isEqualTo(HttpStatus.CREATED)
        assertThat(data(promote.body)!!["stage"]).isEqualTo("artifact-ready")
    }

    @Test
    fun `checkpoint advances the delivery head commit`() {
        provision("wf-delivery-checkpoint")
        val snapshot = data(get("wf-delivery-checkpoint").body)!!
        val head = snapshot["headCommit"] as String
        val checkpoint = post(
            "wf-delivery-checkpoint",
            "/checkpoint",
            """{"expectedHead":"$head","message":"checkpoint","claims":{"paths":[],"diffHash":"sha256:${"a".repeat(64)}"}}""",
        )
        assertThat(checkpoint.statusCode).isEqualTo(HttpStatus.CREATED)
        val data = data(checkpoint.body)!!
        assertThat(data["changed"]).isEqualTo(true)
        assertThat(data["commit"]).isNotEqualTo(head)
    }

    private fun get(workflowId: String): ResponseEntity<Map<String, Any?>> = restTemplate.exchange(
        "/api/factory/workflows/$workflowId/delivery",
        HttpMethod.GET,
        HttpEntity<Void>(headers()),
        jsonType(),
    )

    private fun post(workflowId: String, suffix: String, body: String?): ResponseEntity<Map<String, Any?>> =
        restTemplate.exchange(
            "/api/factory/workflows/$workflowId/delivery$suffix",
            HttpMethod.POST,
            HttpEntity(body, headers()),
            jsonType(),
        )

    private fun headers(): HttpHeaders = HttpHeaders().apply {
        contentType = MediaType.APPLICATION_JSON
        set("x-factory-namespace-id", NAMESPACE)
        set("x-factory-case-id", CASE)
        set("x-factory-actor-id", "tester")
    }

    private fun jsonType(): ParameterizedTypeReference<Map<String, Any?>> =
        object : ParameterizedTypeReference<Map<String, Any?>>() {}

    @Suppress("UNCHECKED_CAST")
    private fun data(body: Map<String, Any?>?): Map<String, Any?>? = body?.get("data") as? Map<String, Any?>

    @Suppress("UNCHECKED_CAST")
    private fun errorCode(body: Map<String, Any?>?): String? =
        (body?.get("error") as? Map<String, Any?>)?.get("code") as? String

    private companion object {
        const val NAMESPACE = "dddddddd-dddd-4ddd-8ddd-dddddddddddd"
        const val CASE = "eeeeeeee-eeee-4eee-8eee-eeeeeeeeeeee"
    }
}
