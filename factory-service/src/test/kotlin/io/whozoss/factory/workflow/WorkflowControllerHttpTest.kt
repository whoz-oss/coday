package io.whozoss.factory.workflow

import io.whozoss.factory.Neo4jDomainIntegrationTest
import io.whozoss.factory.persistence.TenantScope
import io.whozoss.factory.workflow.domain.WorkflowDefinitionRecord
import io.whozoss.factory.workflow.domain.WorkflowDefinitionValidation
import io.whozoss.factory.workflow.domain.WorkflowDefinitionValidator
import io.whozoss.factory.workflow.domain.hashWorkflowDefinition
import io.whozoss.factory.workflow.service.WorkflowService
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
 * HTTP integration tests of the workflow REST surface against a real PostgreSQL
 * instance and a real servlet container.
 *
 * Exercises the trusted boundary (401 `TRUST_CONTEXT_UNAVAILABLE` for a
 * non-loopback anonymous caller), the canonical `{ "data": ... }` success
 * envelope, the `{ "error": { code, message } }` failure envelope and the
 * governed `start` / detail routes.
 */
class WorkflowControllerHttpTest : Neo4jDomainIntegrationTest() {

    @Autowired
    private lateinit var restTemplate: TestRestTemplate

    @Autowired
    private lateinit var service: WorkflowService

    private val namespace = "0d4bd471-df37-43d8-a8f7-c989f95e71d7"
    private val secondNamespace = "1e5ce582-ea48-49e9-b9f8-d0a0b6f82e28"

    private fun jsonType(): ParameterizedTypeReference<Map<String, Any?>> =
        object : ParameterizedTypeReference<Map<String, Any?>>() {}

    private fun headers(withForward: Boolean = false): HttpHeaders = HttpHeaders().apply {
        contentType = MediaType.APPLICATION_JSON
        if (withForward) add("X-Forwarded-For", "203.0.113.9")
    }

    private fun registerDefinition() {
        val raw = linkedMapOf<String, Any?>(
            "schemaVersion" to "1",
            "workflowType" to "wf-http",
            "version" to "1.0.0",
            "title" to "HTTP workflow",
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
        service.registerDefinition(
            scope,
            WorkflowDefinitionRecord(
                workflowType = "wf-http",
                version = "1.0.0",
                definitionHash = hashWorkflowDefinition(validated.definition),
                definition = validated.definition,
            ),
        )
    }

    @Suppress("UNCHECKED_CAST")
    private fun data(body: Map<String, Any?>?): Map<String, Any?> = body?.get("data") as? Map<String, Any?> ?: emptyMap()

    @Test
    fun `anonymous non-loopback caller yields 401 TRUST_CONTEXT_UNAVAILABLE`() {
        val response = restTemplate.exchange(
            "/api/factory/workflow-definitions",
            HttpMethod.GET,
            HttpEntity<Void>(headers(withForward = true)),
            jsonType(),
        )
        assertThat(response.statusCode).isEqualTo(HttpStatus.UNAUTHORIZED)
        val error = response.body?.get("error") as? Map<*, *>
        assertThat(error?.get("code")).isEqualTo("TRUST_CONTEXT_UNAVAILABLE")
    }

    @Test
    fun `definition list uses the data envelope`() {
        registerDefinition()
        val response = restTemplate.exchange(
            "/api/factory/workflow-definitions",
            HttpMethod.GET,
            HttpEntity<Void>(headers()),
            jsonType(),
        )
        assertThat(response.statusCode).isEqualTo(HttpStatus.OK)
        val items = data(response.body)["items"] as? List<*>
        assertThat(items).isNotNull
        assertThat(items!!).hasSizeGreaterThanOrEqualTo(1)
    }

    @Test
    fun `start returns 201 with the created snapshot and detail returns existing`() {
        registerDefinition()
        val startBody = mapOf(
            "workflow" to mapOf(
                "workflowId" to "wf-http-1",
                "workflowType" to "wf-http",
                "title" to "HTTP start",
            ),
            "execution" to mapOf(
                "namespaceId" to namespace,
                "runtimeId" to "agentos-primary",
                "kind" to "agentos",
                "agentId" to "runner",
                "caseId" to "case-http",
            ),
        )
        val start = restTemplate.exchange(
            "/api/factory/workflows/wf-http-1/start",
            HttpMethod.POST,
            HttpEntity(startBody, headers()),
            jsonType(),
        )
        assertThat(start.statusCode).isEqualTo(HttpStatus.CREATED)
        assertThat(data(start.body)["created"]).isEqualTo(true)
        assertThat(data(start.body)["revision"]).isEqualTo(1)

        val detail = restTemplate.exchange(
            "/api/factory/workflows/wf-http-1?namespaceId=$namespace",
            HttpMethod.GET,
            HttpEntity<Void>(headers()),
            jsonType(),
        )
        assertThat(detail.statusCode).isEqualTo(HttpStatus.OK)
        assertThat(data(detail.body)["state"]).isEqualTo("existing")
    }

    @Suppress("UNCHECKED_CAST")
    @Test
    fun `start propagates an optional ticket into the instance and its relations`() {
        registerDefinition()
        val startBody = mapOf(
            "workflow" to mapOf(
                "workflowId" to "wf-http-ticket",
                "workflowType" to "wf-http",
                "title" to "HTTP start ticket",
                "ticket" to "JIRA-7",
            ),
            "execution" to mapOf(
                "namespaceId" to namespace,
                "runtimeId" to "agentos-primary",
                "kind" to "agentos",
                "agentId" to "runner",
            ),
        )
        val start = restTemplate.exchange(
            "/api/factory/workflows/wf-http-ticket/start",
            HttpMethod.POST,
            HttpEntity(startBody, headers()),
            jsonType(),
        )
        assertThat(start.statusCode).isEqualTo(HttpStatus.CREATED)
        val payload = data(start.body)
        val instance = payload["instance"] as Map<String, Any?>
        assertThat(instance["ticket"]).isEqualTo("JIRA-7")
        val relations = payload["relations"] as Map<String, Any?>
        assertThat(relations["ticket"]).isEqualTo("JIRA-7")
    }

    @Test
    fun `start rejects an invalid execution with 400`() {
        registerDefinition()
        val startBody = mapOf(
            "workflow" to mapOf(
                "workflowId" to "wf-http-2",
                "workflowType" to "wf-http",
                "title" to "HTTP start",
            ),
            "execution" to mapOf(
                "namespaceId" to "not-a-uuid",
                "runtimeId" to "agentos-primary",
                "kind" to "agentos",
                "agentId" to "runner",
            ),
        )
        val start = restTemplate.exchange(
            "/api/factory/workflows/wf-http-2/start",
            HttpMethod.POST,
            HttpEntity(startBody, headers()),
            jsonType(),
        )
        assertThat(start.statusCode).isEqualTo(HttpStatus.BAD_REQUEST)
        val error = start.body?.get("error") as? Map<*, *>
        assertThat(error?.get("code")).isEqualTo("INVALID_NAMESPACE_ID")
    }

    // ----- optional namespaceId on the list route ------------------------

    @Suppress("UNCHECKED_CAST")
    private fun listIds(payload: Map<String, Any?>): List<String> =
        (payload["items"] as? List<*>)
            ?.filterIsInstance<Map<String, Any?>>()
            ?.mapNotNull { it["workflowId"] as? String }
            ?: emptyList()

    private fun publish(workflowId: String, namespaceId: String) {
        val body = mapOf(
            "execution" to mapOf(
                "namespaceId" to namespaceId,
                "runtimeId" to "factory-dashboard",
                "kind" to "coday-express",
                "agentId" to "runner",
            ),
            "projection" to mapOf(
                "schemaVersion" to "2",
                "workflowId" to workflowId,
                "workflowType" to "wf-http",
                "title" to "Listed $workflowId",
                "status" to "ready",
                "steps" to listOf(
                    mapOf(
                        "id" to "gate",
                        "name" to "Gate",
                        "status" to "ready",
                        "responsibility" to mapOf("kind" to "human"),
                    ),
                ),
            ),
        )
        val response = restTemplate.exchange(
            "/api/factory/workflows/$workflowId/projection",
            HttpMethod.PUT,
            HttpEntity(body, headers()),
            jsonType(),
        )
        assertThat(response.statusCode).isEqualTo(HttpStatus.CREATED)
    }

    @Test
    fun `list without namespaceId returns workflows of every namespace in the tenant scope`() {
        publish("wf-list-a", namespace)
        publish("wf-list-b", secondNamespace)

        val response = restTemplate.exchange(
            "/api/factory/workflows?state=active",
            HttpMethod.GET,
            HttpEntity<Void>(headers()),
            jsonType(),
        )
        // A loopback-dev trust context carries no namespace: the scope-wide list
        // must succeed (200) and never fail closed with 401 INVALID_NAMESPACE_ID.
        assertThat(response.statusCode).isNotEqualTo(HttpStatus.UNAUTHORIZED)
        assertThat(response.statusCode).isEqualTo(HttpStatus.OK)
        assertThat(response.body?.get("error")).isNull()
        val items = (data(response.body)["items"] as? List<*>)
            ?.filterIsInstance<Map<String, Any?>>()
            ?: emptyList()
        assertThat(items.mapNotNull { it["workflowId"] as? String }).contains("wf-list-a", "wf-list-b")
        // Each item carries its namespace so the cockpit can open its detail.
        assertThat(items.mapNotNull { it["namespaceId"] as? String }).contains(namespace, secondNamespace)
    }

    @Test
    fun `list with namespaceId returns only that namespace`() {
        publish("wf-list-a", namespace)
        publish("wf-list-b", secondNamespace)

        val response = restTemplate.exchange(
            "/api/factory/workflows?namespaceId=$namespace&state=active",
            HttpMethod.GET,
            HttpEntity<Void>(headers()),
            jsonType(),
        )
        assertThat(response.statusCode).isEqualTo(HttpStatus.OK)
        val ids = listIds(data(response.body))
        assertThat(ids).containsExactly("wf-list-a")
    }

    @Test
    fun `tenant scope isolation keeps other scopes invisible to a scope-wide list`() {
        publish("wf-list-a", namespace)

        val otherScope = TenantScope("org-other", "ws-other")
        service.publishProjection(
            otherScope,
            secondNamespace,
            "wf-other-scope",
            mapOf(
                "schemaVersion" to "2",
                "workflowId" to "wf-other-scope",
                "workflowType" to "wf-http",
                "title" to "Other scope",
                "status" to "ready",
                "steps" to listOf(
                    mapOf(
                        "id" to "gate",
                        "name" to "Gate",
                        "status" to "ready",
                        "responsibility" to mapOf("kind" to "human"),
                    ),
                ),
            ),
            0,
            null,
        )

        assertThat(listIds(service.listProjections(scope, null, "active")))
            .contains("wf-list-a")
            .doesNotContain("wf-other-scope")
        assertThat(listIds(service.listProjections(otherScope, null, "active")))
            .containsExactly("wf-other-scope")
    }

    @Test
    fun `explicit cancellation is wired by default since the SSE cutover and reports an unknown attempt`() {
        // Since the final cutover the durable SSE bridge is enabled by default, so
        // the cancellation service is wired and the route no longer answers
        // BRIDGE_CANCELLATION_UNAVAILABLE; an unknown attempt is reported as not found.
        val body = mapOf("namespaceId" to namespace, "expectedRevision" to 1)
        val response = restTemplate.exchange(
            "/api/factory/workflows/wf-cancel/attempts/attempt-1/cancel",
            HttpMethod.POST,
            HttpEntity(body, headers()),
            jsonType(),
        )
        assertThat(response.statusCode).isEqualTo(HttpStatus.NOT_FOUND)
        val error = response.body?.get("error") as? Map<*, *>
        assertThat(error?.get("code")).isEqualTo("ATTEMPT_NOT_FOUND")
    }
}
