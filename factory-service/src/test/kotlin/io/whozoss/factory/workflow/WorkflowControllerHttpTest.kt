package io.whozoss.factory.workflow

import io.whozoss.factory.DomainIntegrationTest
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
class WorkflowControllerHttpTest : DomainIntegrationTest() {

    @Autowired
    private lateinit var restTemplate: TestRestTemplate

    @Autowired
    private lateinit var service: WorkflowService

    private val namespace = "0d4bd471-df37-43d8-a8f7-c989f95e71d7"

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
}
