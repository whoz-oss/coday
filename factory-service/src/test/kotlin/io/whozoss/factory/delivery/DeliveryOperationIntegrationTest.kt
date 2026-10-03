package io.whozoss.factory.delivery

import io.whozoss.factory.Neo4jDomainIntegrationTest
import io.whozoss.factory.delivery.port.DeliveryTargetRegistry
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
 * HTTP integration tests of the delivery-operation control plane (deployments,
 * rollbacks and their approvals) against a real PostgreSQL instance.
 *
 * Exercises trusted-target binding, the target-registry-unavailable 503 path
 * and the rollback request/approval lifecycle.
 */
class DeliveryOperationIntegrationTest : Neo4jDomainIntegrationTest() {

    @Autowired
    private lateinit var restTemplate: TestRestTemplate

    @Autowired
    private lateinit var environmentService: WorkUnitEnvironmentService

    @Autowired
    private lateinit var targetRegistry: DeliveryTargetRegistry

    private fun provision(workflowId: String) {
        environmentService.provision(
            scope,
            ProvisionEnvironmentCommand(
                workflowId = workflowId,
                workUnitId = "wu-1",
                namespaceId = NAMESPACE,
                parentCaseId = CASE,
                integrationBranch = "main",
                branch = "feature/ops",
                createdBy = "tester",
            ),
        )
    }

    private fun registerTarget() {
        targetRegistry.register(
            mapOf(
                "targetId" to "prod-1",
                "environmentKind" to "production",
                "adapterId" to "stub-adapter",
                "adapterTargetRef" to "ref-1",
                "supportsRollback" to true,
                "verificationSuiteId" to "suite-1",
                "verificationSuiteHash" to "sha256:${"f".repeat(64)}",
            ),
        )
    }

    @Test
    fun `deploy without a configured target registry yields 503`() {
        provision("wf-ops-deploy")
        val response = post(
            "wf-ops-deploy",
            "/deploy",
            """
            {
              "expectedRevision": 1,
              "idempotencyKey": "deploy-1",
              "targetId": "prod-1",
              "artifactRef": {
                "digest": "sha256:${"a".repeat(64)}",
                "mediaType": "application/octet-stream",
                "producerRef": "builder-1",
                "buildRef": "build-1",
                "sourceCommit": "${"c".repeat(40)}"
              },
              "releaseRef": {
                "releaseId": "release-1",
                "artifactDigest": "sha256:${"a".repeat(64)}",
                "sourceCommit": "${"c".repeat(40)}",
                "approvedEvidenceId": "ev-1"
              }
            }
            """.trimIndent(),
        )
        assertThat(response.statusCode).isEqualTo(HttpStatus.SERVICE_UNAVAILABLE)
        assertThat(errorCode(response.body)).isEqualTo("DELIVERY_TARGET_REGISTRY_UNAVAILABLE")
    }

    @Test
    fun `rollback request is created and approved`() {
        provision("wf-ops-rollback")
        registerTarget()
        val rollback = post("wf-ops-rollback", "/rollbacks", rollbackBody())
        assertThat(rollback.statusCode).isEqualTo(HttpStatus.CREATED)
        val request = data(rollback.body)!!
        assertThat(request["status"]).isEqualTo("requested")
        val rollbackRequestId = request["rollbackRequestId"] as String

        val approved = post(
            "wf-ops-rollback",
            "/rollbacks/$rollbackRequestId/approve",
            """{"expectedRevision":1,"idempotencyKey":"approve-1"}""",
        )
        assertThat(approved.statusCode).isEqualTo(HttpStatus.CREATED)
        assertThat(data(approved.body)!!["status"]).isEqualTo("approved")
    }

    @Test
    fun `rollback request with an unknown target yields 404`() {
        provision("wf-ops-unknown-target")
        registerTarget()
        val response = post("wf-ops-unknown-target", "/rollbacks", rollbackBody(targetId = "unknown-target"))
        assertThat(response.statusCode).isEqualTo(HttpStatus.NOT_FOUND)
        assertThat(errorCode(response.body)).isEqualTo("DELIVERY_TARGET_NOT_FOUND")
    }

    private fun rollbackBody(targetId: String = "prod-1"): String = """
        {
          "expectedRevision": 1,
          "idempotencyKey": "rollback-1",
          "targetId": "$targetId",
          "deploymentRef": {"operationId":"dop_1","kind":"deployment","state":"succeeded","targetHash":"sha256:${"f".repeat(64)}","sourceCommit":"${"c".repeat(40)}","artifactDigest":"sha256:${"a".repeat(64)}"},
          "priorArtifactRef": {"digest":"sha256:${"b".repeat(64)}","producerRef":"builder-1","buildRef":"build-0","sourceCommit":"${"c".repeat(40)}","mediaType":"application/octet-stream"},
          "priorReleaseRef": {"releaseId":"release-0","artifactDigest":"sha256:${"b".repeat(64)}","sourceCommit":"${"c".repeat(40)}","approvedEvidenceId":"ev-0"},
          "reasonCode": "incident"
        }
    """.trimIndent()

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
        const val NAMESPACE = "ffffffff-ffff-4fff-8fff-ffffffffffff"
        const val CASE = "12345678-1234-4123-8123-123456789012"
    }
}
