package io.whozoss.factory.workstreamservice

import io.whozoss.factory.Neo4jDomainIntegrationTest
import io.whozoss.factory.agentattempt.domain.DurableAgentAttempt
import io.whozoss.factory.agentattempt.service.DurableAgentAttemptService
import io.whozoss.factory.persistence.TenantScope
import io.whozoss.factory.workflow.domain.WorkflowDefinitionRecord
import io.whozoss.factory.workflow.domain.WorkflowDefinitionValidation
import io.whozoss.factory.workflow.domain.WorkflowDefinitionValidator
import io.whozoss.factory.workflow.domain.hashWorkflowDefinition
import io.whozoss.factory.workflow.service.WorkflowService
import io.whozoss.factory.workstream.domain.Workstream
import io.whozoss.factory.workstream.domain.WorkstreamStatus
import io.whozoss.factory.workstream.persistence.Neo4jWorkstreamRepository
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.BeforeEach
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
import org.springframework.test.context.TestPropertySource

/**
 * HTTP integration tests of the `FACTORY_WORKSTREAM` facade and of its credential boundary.
 *
 * The tenant is the one the Neo4j test harness uses (`org-local-dev` / `ws-default`), configured as the
 * workstream service tenant. Read and start are granted; `request_retry` is deliberately NOT granted so the
 * per-operation permission can be asserted.
 */
@TestPropertySource(
    properties = [
        "factory.security.http-api-submission.enabled=true",
        "factory.security.http-api-submission.bearer-token=worker-bearer-token-at-least-32-bytes",
        "factory.security.http-api-submission.service-identity-id=agentos-http-api-test",
        "factory.security.workstream-service.enabled=true",
        "factory.security.workstream-service.bearer-token=workstream-bearer-token-at-least-32-bytes",
        "factory.security.workstream-service.service-identity-id=agentos-workstream-test",
        "factory.security.workstream-service.organization-id=org-local-dev",
        "factory.security.workstream-service.workstream-id=ws-default",
        "factory.security.workstream-service.permissions=read,start_workflow",
    ],
)
class WorkstreamServiceControllerHttpTest : Neo4jDomainIntegrationTest() {

    @Autowired
    private lateinit var restTemplate: TestRestTemplate

    @Autowired
    private lateinit var workflowService: WorkflowService

    @Autowired
    private lateinit var durableAttempts: DurableAgentAttemptService

    @Autowired
    private lateinit var workstreamRepository: Neo4jWorkstreamRepository

    private val workstreamToken = "workstream-bearer-token-at-least-32-bytes"
    private val workerToken = "worker-bearer-token-at-least-32-bytes"
    private val namespace = "0d4bd471-df37-43d8-a8f7-c989f95e71d7"
    private val secondNamespace = "1e5ce582-ea48-49e9-b9f8-d0a0b6f82e28"
    private val base = "/api/factory/workstream-service"

    @BeforeEach
    fun registerConfiguredWorkstream() {
        workstreamRepository.create(
            scope,
            Workstream(
                organizationId = scope.organizationId,
                workstreamId = scope.workstreamId,
                namespaceId = namespace,
                name = "Configured workstream",
                status = WorkstreamStatus.ACTIVE,
            ),
        )
    }

    private fun jsonType(): ParameterizedTypeReference<Map<String, Any?>> =
        object : ParameterizedTypeReference<Map<String, Any?>>() {}

    private fun headers(token: String?, key: String? = null): HttpHeaders = HttpHeaders().apply {
        contentType = MediaType.APPLICATION_JSON
        if (token != null) setBearerAuth(token)
        if (key != null) set("Idempotency-Key", key)
    }

    private fun get(path: String, token: String? = workstreamToken): ResponseEntity<Map<String, Any?>> =
        restTemplate.exchange(path, HttpMethod.GET, HttpEntity<Void>(headers(token)), jsonType())

    private fun post(path: String, body: Any?, token: String? = workstreamToken, key: String? = null): ResponseEntity<Map<String, Any?>> =
        restTemplate.exchange(path, HttpMethod.POST, HttpEntity(body, headers(token, key)), jsonType())

    @Suppress("UNCHECKED_CAST")
    private fun data(body: Map<String, Any?>?): Map<String, Any?> = body?.get("data") as? Map<String, Any?> ?: emptyMap()

    @Suppress("UNCHECKED_CAST")
    private fun errorCode(body: Map<String, Any?>?): String? =
        (body?.get("error") as? Map<String, Any?>)?.get("code") as? String

    private fun registerDefinition(type: String = "wf-svc") {
        val raw = linkedMapOf<String, Any?>(
            "schemaVersion" to "1",
            "workflowType" to type,
            "version" to "1.0.0",
            "title" to "Service workflow",
            "steps" to listOf(
                linkedMapOf(
                    "id" to "gate",
                    "name" to "Gate",
                    "responsibility" to linkedMapOf("kind" to "human", "name" to "reviewer"),
                    "dependsOn" to emptyList<String>(),
                ),
            ),
        )
        val validated = WorkflowDefinitionValidator.validate(raw) as WorkflowDefinitionValidation.Valid
        workflowService.registerDefinition(
            scope,
            WorkflowDefinitionRecord(
                workflowType = type,
                version = "1.0.0",
                definitionHash = hashWorkflowDefinition(validated.definition),
                definition = validated.definition,
            ),
        )
    }

    private fun publish(workflowId: String, namespaceId: String, target: TenantScope = scope, stepStatus: String = "ready") {
        workflowService.publishProjection(
            target,
            namespaceId,
            workflowId,
            mapOf(
                "schemaVersion" to "2",
                "workflowId" to workflowId,
                "workflowType" to "wf-svc",
                "title" to "Listed $workflowId",
                "status" to "ready",
                "steps" to listOf(
                    mapOf(
                        "id" to "gate",
                        "name" to "Gate",
                        "status" to stepStatus,
                        "responsibility" to mapOf("kind" to "human"),
                    ),
                ),
            ),
            0,
            null,
        )
    }

    // ----- authentication and credential separation ----------------------

    @Test
    fun `a missing or wrong bearer is refused before any facade logic`() {
        listOf<String?>(null, "wrong-token-wrong-token-wrong-token-000").forEach { token ->
            val response = get("$base/workflows", token)
            assertThat(response.statusCode.is2xxSuccessful).describedAs("token=$token").isFalse()
        }
    }

    @Test
    fun `the worker credential cannot reach the workstream facade`() {
        val response = get("$base/workflows", workerToken)
        assertThat(response.statusCode).isEqualTo(HttpStatus.FORBIDDEN)
        assertThat(errorCode(response.body)).isEqualTo("SERVICE_ROUTE_FORBIDDEN")
    }

    @Test
    fun `the workstream credential cannot reach the worker route or any other Factory route`() {
        val worker = post("/api/factory/agent-step-results", mapOf("attemptId" to "x", "result" to mapOf<String, Any>()), key = "k")
        assertThat(worker.statusCode).isEqualTo(HttpStatus.FORBIDDEN)
        assertThat(errorCode(worker.body)).isEqualTo("SERVICE_ROUTE_FORBIDDEN")

        listOf("/api/factory/workflows?namespaceId=$namespace", "/api/factory/workstreams", "/api/factory/workflow-definitions")
            .forEach { path ->
                val response = get(path)
                assertThat(response.statusCode).describedAs(path).isEqualTo(HttpStatus.FORBIDDEN)
                assertThat(errorCode(response.body)).describedAs(path).isEqualTo("SERVICE_ROUTE_FORBIDDEN")
            }
    }

    // ----- reads: tenant fencing ------------------------------------------

    @Test
    fun `list returns only workflows of the configured namespace and hides other namespaces and tenants`() {
        publish("wf-svc-a", namespace)
        publish("wf-svc-b", secondNamespace)
        publish("wf-svc-foreign", namespace, TenantScope("org-other", "ws-other"))

        val response = get("$base/workflows")

        assertThat(response.statusCode).isEqualTo(HttpStatus.OK)
        @Suppress("UNCHECKED_CAST")
        val ids = (data(response.body)["items"] as List<Map<String, Any?>>).map { it["workflowId"] }
        assertThat(ids).contains("wf-svc-a").doesNotContain("wf-svc-b", "wf-svc-foreign")
    }

    @Test
    fun `a foreign tenant workflow is not found even when its id and namespace are guessed`() {
        publish("wf-svc-foreign", namespace, TenantScope("org-other", "ws-other"))

        val response = get("$base/workflows/wf-svc-foreign")

        assertThat(response.statusCode).isEqualTo(HttpStatus.OK)
        assertThat(data(response.body)["state"]).isEqualTo("absent")
    }

    @Test
    fun `get_workflow exposes blockers and never advertises a retry the credential cannot execute`() {
        publish("wf-svc-failed", namespace, stepStatus = "failed")

        val response = get("$base/workflows/wf-svc-failed")

        assertThat(response.statusCode).isEqualTo(HttpStatus.OK)
        val payload = data(response.body)
        assertThat(payload["state"]).isEqualTo("existing")
        assertThat(payload["allowedActions"] as List<*>).isEmpty()
        assertThat(payload.toString()).contains("ATTEMPT_FAILED")
    }

    @Test
    fun `a caller supplied namespace never influences the configured namespace`() {
        publish("wf-svc-a", namespace)
        publish("wf-svc-b", secondNamespace)
        val response = get("$base/workflows?namespaceId=$secondNamespace")
        @Suppress("UNCHECKED_CAST")
        val ids = (data(response.body)["items"] as List<Map<String, Any?>>).map { it["workflowId"] }
        assertThat(ids).contains("wf-svc-a").doesNotContain("wf-svc-b")
    }

    @Test
    fun `attempts are secret free and filtered by step`() {
        durableAttempts.register(
            scope,
            DurableAgentAttempt(
                attemptId = "svc-attempt-1", caseId = "case-1", namespaceId = namespace, workflowId = "wf-svc-att",
                stepId = "step-1", attemptNumber = 1, agentName = "builder",
                capabilityToken = "cap-secret", brief = "brief-secret",
            ),
        )
        durableAttempts.register(
            scope,
            DurableAgentAttempt(
                attemptId = "svc-attempt-2", caseId = "case-2", namespaceId = namespace, workflowId = "wf-svc-att",
                stepId = "step-2", attemptNumber = 1, agentName = "builder",
            ),
        )

        val response = get("$base/workflows/wf-svc-att/attempts?stepId=step-2")

        assertThat(response.statusCode).isEqualTo(HttpStatus.OK)
        @Suppress("UNCHECKED_CAST")
        val items = data(response.body)["items"] as List<Map<String, Any?>>
        assertThat(items.map { it["attemptId"] }).containsExactly("svc-attempt-2")
        val all = get("$base/workflows/wf-svc-att/attempts").body.toString()
        assertThat(all).doesNotContain("cap-secret").doesNotContain("brief-secret").doesNotContain("capabilityToken")
    }

    @Test
    fun `a workstream other than the configured one is a boundary violation`() {
        val response = get("$base/workstreams/some-other-workstream")
        assertThat(response.statusCode).isEqualTo(HttpStatus.FORBIDDEN)
        assertThat(errorCode(response.body)).isEqualTo("WORKSTREAM_BOUNDARY_VIOLATION")
    }

    @Test
    fun `human actions are information only and never answerable from this channel`() {
        publish("wf-svc-human", namespace)
        val response = get("$base/workflows/wf-svc-human/human-actions")
        assertThat(response.statusCode).isEqualTo(HttpStatus.OK)
        assertThat(data(response.body)["items"] as List<*>).isEmpty()
    }

    // ----- writes: permissions, scope, idempotence ------------------------

    @Test
    fun `start creates a run without any runtime case attribution and is idempotent`() {
        registerDefinition()
        val body = mapOf("workflowType" to "wf-svc", "title" to "From service")

        val first = post("$base/workflows", body, key = "start-key-1")
        val replay = post("$base/workflows", body, key = "start-key-1")

        assertThat(first.statusCode).isEqualTo(HttpStatus.CREATED)
        assertThat(data(first.body)["created"]).isEqualTo(true)
        assertThat(replay.statusCode).isEqualTo(HttpStatus.OK)
        assertThat(data(replay.body)["idempotent"]).isEqualTo(true)
        assertThat(data(replay.body)["workflowId"]).isEqualTo(data(first.body)["workflowId"])
    }

    @Test
    fun `the same start key with a different payload is refused`() {
        registerDefinition()
        post("$base/workflows", mapOf("workflowType" to "wf-svc", "title" to "One"), key = "start-key-2")

        val divergent = post(
            "$base/workflows",
            mapOf("workflowType" to "wf-svc", "title" to "Two"),
            key = "start-key-2",
        )

        assertThat(divergent.statusCode).isEqualTo(HttpStatus.CONFLICT)
        assertThat(errorCode(divergent.body)).isEqualTo("WORKFLOW_IDENTITY_CONFLICT")
    }

    @Test
    fun `start requires an idempotency key and refuses unknown fields`() {
        registerDefinition()
        val noKey = post("$base/workflows", mapOf("workflowType" to "wf-svc"))
        assertThat(noKey.statusCode).isEqualTo(HttpStatus.BAD_REQUEST)

        val extra = post(
            "$base/workflows",
            mapOf("workflowType" to "wf-svc", "caseId" to "forged", "actorId" to "forged"),
            key = "start-key-3",
        )
        assertThat(extra.statusCode).isEqualTo(HttpStatus.BAD_REQUEST)

        val legacyNamespace = post(
            "$base/workflows",
            mapOf("namespaceId" to namespace, "workflowType" to "wf-svc"),
            key = "start-key-legacy",
        )
        assertThat(legacyNamespace.statusCode).isEqualTo(HttpStatus.BAD_REQUEST)
    }

    @Test
    fun `the facade fails closed when the registered namespace mapping is invalid`() {
        workstreamRepository.save(
            scope,
            workstreamRepository.findDomain(scope, scope.workstreamId)!!.copy(namespaceId = "not-a-uuid"),
        )

        val response = get("$base/workflows")

        assertThat(response.statusCode).isEqualTo(HttpStatus.FORBIDDEN)
        assertThat(errorCode(response.body)).isEqualTo("WORKSTREAM_NAMESPACE_MAPPING_INVALID")
    }

    @Test
    fun `the facade fails closed when the configured workstream is inactive`() {
        workstreamRepository.save(
            scope,
            workstreamRepository.findDomain(scope, scope.workstreamId)!!.copy(status = WorkstreamStatus.PAUSED),
        )

        val response = get("$base/workflows")

        assertThat(response.statusCode).isEqualTo(HttpStatus.FORBIDDEN)
        assertThat(errorCode(response.body)).isEqualTo("WORKSTREAM_NAMESPACE_MAPPING_INVALID")
    }

    @Test
    fun `request_agent_retry is refused when the permission is not granted`() {
        publish("wf-svc-retry", namespace, stepStatus = "blocked")

        val response = post(
            "$base/workflows/wf-svc-retry/retries",
            mapOf("stepId" to "gate", "expectedRevision" to 1, "reasonCode" to "manual"),
        )

        assertThat(response.statusCode).isEqualTo(HttpStatus.FORBIDDEN)
        assertThat(errorCode(response.body)).isEqualTo("FORBIDDEN")
    }
}
