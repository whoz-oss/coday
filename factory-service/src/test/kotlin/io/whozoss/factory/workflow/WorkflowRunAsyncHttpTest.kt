package io.whozoss.factory.workflow

import com.fasterxml.jackson.databind.ObjectMapper
import io.whozoss.factory.Neo4jDomainIntegrationTest
import io.whozoss.factory.agentattempt.persistence.SpringDataNeo4jOutboxRepository
import io.whozoss.factory.agentattempt.service.OutboxDrainService
import io.whozoss.factory.agentattempt.service.OutboxDrainWorker
import io.whozoss.factory.proxy.AgentOsProxyClient
import io.whozoss.factory.workflow.domain.ControllerExecutionInput
import io.whozoss.factory.workflow.domain.WorkflowDefinitionRecord
import io.whozoss.factory.workflow.domain.WorkflowDefinitionValidation
import io.whozoss.factory.workflow.domain.WorkflowDefinitionValidator
import io.whozoss.factory.workflow.domain.WorkflowStartCommand
import io.whozoss.factory.workflow.domain.WorkflowStatuses
import io.whozoss.factory.workflow.domain.hashWorkflowDefinition
import io.whozoss.factory.workflow.service.SessionRunService
import io.whozoss.factory.workflow.service.SessionRunSubmissionService
import io.whozoss.factory.workflow.service.WorkflowService
import java.nio.file.Path
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.boot.test.web.client.TestRestTemplate
import org.springframework.core.ParameterizedTypeReference
import org.springframework.http.HttpEntity
import org.springframework.http.HttpHeaders
import org.springframework.http.HttpMethod
import org.springframework.http.HttpStatus
import org.springframework.http.MediaType

/**
 * HTTP integration tests of the asynchronous, durable `POST /run` entry point
 * (Lot C / étape 3).
 *
 * The endpoint must answer `202 Accepted` with a tracking identity without
 * holding the connection for the agent turn, and the submission must be durable:
 * it is enqueued in the transactional outbox and drained by the background worker
 * (here driven manually), so an undrained submission survives a restart.
 */
class WorkflowRunAsyncHttpTest : Neo4jDomainIntegrationTest() {

    @Autowired
    private lateinit var restTemplate: TestRestTemplate

    @Autowired
    private lateinit var workflowService: WorkflowService

    @Autowired
    private lateinit var sessionRunService: SessionRunService

    @Autowired
    private lateinit var outbox: SpringDataNeo4jOutboxRepository

    @Autowired
    private lateinit var outboxDrainService: OutboxDrainService

    @Autowired
    private lateinit var agentOsProxyClient: AgentOsProxyClient

    @Autowired
    private lateinit var objectMapper: ObjectMapper

    @TempDir
    lateinit var repoRoot: Path

    private val namespace = "3c7a9e3a-9e2b-4c1d-8f7a-run-async-http"

    private fun jsonType(): ParameterizedTypeReference<Map<String, Any?>> =
        object : ParameterizedTypeReference<Map<String, Any?>>() {}

    private fun headers(): HttpHeaders = HttpHeaders().apply { contentType = MediaType.APPLICATION_JSON }

    @Suppress("UNCHECKED_CAST")
    private fun data(body: Map<String, Any?>?): Map<String, Any?> = body?.get("data") as? Map<String, Any?> ?: emptyMap()

    private fun startSession(workflowId: String) {
        val raw = linkedMapOf<String, Any?>(
            "schemaVersion" to "1",
            "workflowType" to "wf-run-async",
            "version" to "1.0.0",
            "title" to "Async HTTP session",
            "steps" to listOf(
                linkedMapOf(
                    "id" to "gate",
                    "name" to "Gate",
                    "responsibility" to linkedMapOf("kind" to "human", "name" to "reviewer"),
                    "dependsOn" to emptyList<String>(),
                ),
            ),
        )
        val valid = WorkflowDefinitionValidator.validate(raw) as WorkflowDefinitionValidation.Valid
        workflowService.registerDefinition(
            scope,
            WorkflowDefinitionRecord(
                workflowType = "wf-run-async",
                version = "1.0.0",
                definitionHash = hashWorkflowDefinition(valid.definition),
                definition = valid.definition,
            ),
        )
        workflowService.start(
            scope,
            namespace,
            WorkflowStartCommand(workflowId = workflowId, workflowType = "wf-run-async", title = "Async HTTP session"),
            ControllerExecutionInput(runtimeId = "test-runtime", kind = "agentos", agentId = "runner", namespaceId = namespace),
        )
    }

    @Test
    fun `run answers 202 with a tracking identity and durably enqueues the submission`() {
        val workflowId = "wf-run-async-1"
        startSession(workflowId)
        val body = mapOf("namespaceId" to namespace, "repoRoot" to repoRoot.toAbsolutePath().toString())

        val response = restTemplate.exchange(
            "/api/factory/workflows/$workflowId/run",
            HttpMethod.POST,
            HttpEntity(body, headers()),
            jsonType(),
        )

        assertThat(response.statusCode).isEqualTo(HttpStatus.ACCEPTED)
        val payload = data(response.body)
        assertThat(payload["status"]).isEqualTo("accepted")
        assertThat(payload["workflowId"]).isEqualTo(workflowId)
        assertThat(payload["namespaceId"]).isEqualTo(namespace)
        assertThat(payload["submissionId"]).isNotNull

        // The submission is durable: the outbox carries a pending event.
        val events = outbox.findAllByOrganization("org-local-dev")
        assertThat(events)
            .anyMatch { it.eventType == SessionRunSubmissionService.SESSION_RUN_REQUESTED && it.status == "pending" }
    }

    @Test
    fun `a durable submission is drained by the background worker and advances the session`() {
        val workflowId = "wf-run-async-2"
        startSession(workflowId)
        val body = mapOf("namespaceId" to namespace, "repoRoot" to repoRoot.toAbsolutePath().toString())

        val response = restTemplate.exchange(
            "/api/factory/workflows/$workflowId/run",
            HttpMethod.POST,
            HttpEntity(body, headers()),
            jsonType(),
        )
        assertThat(response.statusCode).isEqualTo(HttpStatus.ACCEPTED)

        // No session work has happened yet (the connection returned immediately).
        assertThat(sessionRunService.sessionState(scope, namespace, workflowId)!!.status).isNotEqualTo(WorkflowStatuses.WAITING_HUMAN)

        // The bounded, replayable worker drains the submission and runs the DAG.
        OutboxDrainWorker(outboxDrainService, sessionRunService, agentOsProxyClient, objectMapper).drain()

        val state = sessionRunService.sessionState(scope, namespace, workflowId)!!
        assertThat(state.status).isEqualTo(WorkflowStatuses.WAITING_HUMAN)
        assertThat(state.steps.single { it.stepId == "gate" }.status).isEqualTo(WorkflowStatuses.WAITING_HUMAN)
        assertThat(outbox.findAllByOrganization("org-local-dev"))
            .anyMatch { it.eventType == SessionRunSubmissionService.SESSION_RUN_REQUESTED && it.status == "dispatched" }
    }
}
