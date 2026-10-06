package io.whozoss.factory.workflow

import io.whozoss.factory.Neo4jDomainIntegrationTest
import io.whozoss.factory.agentattempt.domain.DurableAgentAttempt
import io.whozoss.factory.agentattempt.service.DurableAgentAttemptService
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
import org.springframework.test.context.TestPropertySource

/**
 * HTTP integration tests of the authoritative workflow actions surface and of
 * the cost-control pass-through.
 *
 * The AgentOS proxy is pointed at an unreachable address so the graceful 503
 * degradation path is exercised deterministically, without depending on a live
 * AgentOS.
 */
@TestPropertySource(properties = ["factory.proxy.agentos-url=http://127.0.0.1:1"])
class WorkflowActionsHttpTest : Neo4jDomainIntegrationTest() {

    @Autowired
    private lateinit var restTemplate: TestRestTemplate

    @Autowired
    private lateinit var service: WorkflowService

    @Autowired
    private lateinit var durableAgentAttemptService: DurableAgentAttemptService

    private val namespace = "0d4bd471-df37-43d8-a8f7-c989f95e71d7"

    private fun jsonType(): ParameterizedTypeReference<Map<String, Any?>> =
        object : ParameterizedTypeReference<Map<String, Any?>>() {}

    private fun headers(): HttpHeaders = HttpHeaders().apply { contentType = MediaType.APPLICATION_JSON }

    @Suppress("UNCHECKED_CAST")
    private fun data(body: Map<String, Any?>?): Map<String, Any?> = body?.get("data") as? Map<String, Any?> ?: emptyMap()

    @Suppress("UNCHECKED_CAST")
    private fun actions(payload: Map<String, Any?>): List<Map<String, Any?>> =
        (payload["allowedActions"] as? List<*>)?.filterIsInstance<Map<String, Any?>>() ?: emptyList()

    @Suppress("UNCHECKED_CAST")
    private fun blockers(payload: Map<String, Any?>): List<Map<String, Any?>> =
        (payload["blockers"] as? List<*>)?.filterIsInstance<Map<String, Any?>>() ?: emptyList()

    private fun publish(workflowId: String, stepStatus: String = "ready") {
        service.publishProjection(
            scope,
            namespace,
            workflowId,
            mapOf(
                "schemaVersion" to "2",
                "workflowId" to workflowId,
                "workflowType" to "wf-actions",
                "title" to "Actions $workflowId",
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

    @Test
    fun `actions expose a retry action and a blocker for a blocked step`() {
        publish("wf-actions-blocked", stepStatus = "blocked")

        val response = restTemplate.exchange(
            "/api/factory/workflows/wf-actions-blocked/actions?namespaceId=$namespace",
            HttpMethod.GET,
            HttpEntity<Void>(headers()),
            jsonType(),
        )

        assertThat(response.statusCode).isEqualTo(HttpStatus.OK)
        val payload = data(response.body)
        val retry = actions(payload).single { it["type"] == "retry" }
        assertThat(retry["stepId"]).isEqualTo("gate")
        assertThat(retry["expectedRevision"]).isEqualTo(1)
        assertThat(blockers(payload).map { it["code"] }).contains("STEP_BLOCKED")
    }

    @Test
    fun `actions return an empty read model for a fully completed workflow`() {
        publish("wf-actions-done", stepStatus = "completed")

        val response = restTemplate.exchange(
            "/api/factory/workflows/wf-actions-done/actions?namespaceId=$namespace",
            HttpMethod.GET,
            HttpEntity<Void>(headers()),
            jsonType(),
        )

        assertThat(response.statusCode).isEqualTo(HttpStatus.OK)
        val payload = data(response.body)
        assertThat(actions(payload)).isEmpty()
        assertThat(blockers(payload)).isEmpty()
    }

    @Test
    fun `actions return a 404 for an unknown workflow`() {
        val response = restTemplate.exchange(
            "/api/factory/workflows/wf-actions-unknown/actions?namespaceId=$namespace",
            HttpMethod.GET,
            HttpEntity<Void>(headers()),
            jsonType(),
        )

        assertThat(response.statusCode).isEqualTo(HttpStatus.NOT_FOUND)
        val error = response.body?.get("error") as? Map<*, *>
        assertThat(error?.get("code")).isEqualTo("WORKFLOW_NOT_FOUND")
    }

    @Test
    fun `cost stop degrades to a clean 503 when AgentOS is unavailable`() {
        publish("wf-actions-cost")
        durableAgentAttemptService.register(
            scope,
            DurableAgentAttempt(
                attemptId = "attempt-1",
                caseId = "case-1",
                namespaceId = namespace,
                workflowId = "wf-actions-cost",
                stepId = "gate",
                attemptNumber = 1,
                agentName = "agent",
            ),
        )

        val response = restTemplate.exchange(
            "/api/factory/workflows/wf-actions-cost/cost/stop?namespaceId=$namespace",
            HttpMethod.POST,
            HttpEntity(mapOf<String, Any?>(), headers()),
            jsonType(),
        )

        assertThat(response.statusCode).isEqualTo(HttpStatus.SERVICE_UNAVAILABLE)
        val error = response.body?.get("error") as? Map<*, *>
        assertThat(error?.get("code")).isEqualTo("SERVICE_UNAVAILABLE")
        assertThat(error?.get("message")).isEqualTo("Usage tracking is disabled")
    }

    @Test
    fun `cost continue with no bound case reports NO_RUN_CASE`() {
        publish("wf-actions-nocase")

        val response = restTemplate.exchange(
            "/api/factory/workflows/wf-actions-nocase/cost/continue?namespaceId=$namespace",
            HttpMethod.POST,
            HttpEntity(mapOf("expectedThreshold" to 10.0), headers()),
            jsonType(),
        )

        assertThat(response.statusCode).isEqualTo(HttpStatus.CONFLICT)
        val error = response.body?.get("error") as? Map<*, *>
        assertThat(error?.get("code")).isEqualTo("NO_RUN_CASE")
    }
}
