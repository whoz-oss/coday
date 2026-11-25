package io.whozoss.factory.workflow

import io.whozoss.factory.Neo4jDomainIntegrationTest
import io.whozoss.factory.agentattempt.domain.AgentAttemptStatus
import io.whozoss.factory.agentattempt.domain.DurableAgentAttempt
import io.whozoss.factory.agentattempt.persistence.SpringDataNeo4jOutboxRepository
import io.whozoss.factory.agentattempt.service.DurableAgentAttemptService
import io.whozoss.factory.workflow.domain.WorkflowInstanceRecord
import io.whozoss.factory.workflow.persistence.WorkflowRepository
import io.whozoss.factory.workflow.service.SessionRunSubmissionService
import io.whozoss.factory.workstream.WorkstreamService
import io.whozoss.factory.workstream.web.CreateWorkstreamRequest
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
import org.springframework.test.context.TestPropertySource

/**
 * HTTP boundary of the explicit Stop (`POST .../attempts/{attemptId}/cancel`) for WZ-36379: the committed
 * cancellation always enqueues a durable continuation (also on an idempotent replay), the response
 * reports whether AgentOS acknowledged the kill, and only the user who launched the run may stop it.
 *
 * AgentOS is pointed at an unreachable address: the kill is refused (not acknowledged) yet the cancellation
 * itself still commits. A default repository root stands in for the namespace root AgentOS would resolve.
 * The caller of every request is the fixed `local`-mode test user, so the run is owned by that user.
 */
@TestPropertySource(
    properties = [
        "factory.proxy.agentos-url=http://127.0.0.1:1",
        "factory.session.default-repo-root=/tmp/factory-stop-cancel-repo",
    ],
)
class WorkflowCancelAttemptHttpTest : Neo4jDomainIntegrationTest() {

    @Autowired private lateinit var restTemplate: TestRestTemplate
    @Autowired private lateinit var attempts: DurableAgentAttemptService
    @Autowired private lateinit var outbox: SpringDataNeo4jOutboxRepository
    @Autowired private lateinit var workstreams: WorkstreamService
    @Autowired private lateinit var workflows: WorkflowRepository

    private val namespace = "0d4bd471-df37-43d8-a8f7-c989f95e71d7"
    private val workflowId = "wf-cancel-http"
    private val foreignWorkflowId = "wf-cancel-http-foreign"
    private val attemptId = "attempt-cancel-http"
    private val foreignAttemptId = "attempt-cancel-http-foreign"

    @BeforeEach
    fun seed() {
        workstreams.create(
            scope,
            CreateWorkstreamRequest(slug = scope.workstreamId, name = "Cancel fixture", status = "active", namespaceId = namespace),
        )
        seedRunningAttempt(workflowId, attemptId, owner = TEST_USER_ID)
        seedRunningAttempt(foreignWorkflowId, foreignAttemptId, owner = "someone-else")
    }

    private fun seedRunningAttempt(workflowId: String, attemptId: String, owner: String) {
        workflows.insertInstance(
            scope,
            WorkflowInstanceRecord(
                namespaceId = namespace,
                workflowId = workflowId,
                revision = 1,
                status = "active",
                creationCommandHash = "cancel-http-$workflowId",
                instance = mapOf("controllerRequest" to mapOf("actorId" to owner)),
                projection = emptyMap(),
            ),
        )
        attempts.register(
            scope,
            DurableAgentAttempt(
                attemptId = attemptId,
                caseId = "case-$attemptId",
                namespaceId = namespace,
                workflowId = workflowId,
                stepId = "step-1",
                attemptNumber = 1,
                agentName = "architect",
            ),
        )
        attempts.claim(scope, namespace, workflowId, "step-1", attemptId, "worker", leaseTtlMs = 60_000)
        attempts.transition(scope, namespace, workflowId, "step-1", attemptId, "worker", AgentAttemptStatus.STARTING)
        attempts.transition(scope, namespace, workflowId, "step-1", attemptId, "worker", AgentAttemptStatus.RUNNING)
    }

    private fun jsonType() = object : ParameterizedTypeReference<Map<String, Any?>>() {}

    @Suppress("UNCHECKED_CAST")
    private fun data(body: Map<String, Any?>?): Map<String, Any?> = body?.get("data") as? Map<String, Any?> ?: emptyMap()

    @Suppress("UNCHECKED_CAST")
    private fun error(body: Map<String, Any?>?): Map<String, Any?> = body?.get("error") as? Map<String, Any?> ?: emptyMap()

    private fun cancel(workflowId: String, attemptId: String, revision: Int) = restTemplate.exchange(
        "/api/factory/workflows/$workflowId/attempts/$attemptId/cancel",
        HttpMethod.POST,
        HttpEntity(
            mapOf("namespaceId" to namespace, "expectedRevision" to revision),
            HttpHeaders().apply { contentType = MediaType.APPLICATION_JSON },
        ),
        jsonType(),
    )

    private fun continuations(attemptId: String) = outbox.findAllByOrganization(ORGANIZATION_ID).filter {
        it.eventType == SessionRunSubmissionService.SESSION_RUN_REQUESTED && it.payload.contains("attempt-cancelled:$attemptId")
    }

    @Test
    fun `cancel commits, reports the refused kill and durably enqueues one continuation, also on replay`() {
        val revision = attempts.find(scope, namespace, workflowId, "step-1", attemptId)!!.revision

        val first = cancel(workflowId, attemptId, revision)

        assertThat(first.statusCode).withFailMessage { "cancel answered ${first.statusCode}: ${first.body}" }.isEqualTo(HttpStatus.OK)
        assertThat(data(first.body)["status"]).isEqualTo("interrupted")
        assertThat(data(first.body)["idempotent"]).isEqualTo(false)
        assertThat(data(first.body)["killAcknowledged"]).isEqualTo(false)
        val enqueued = continuations(attemptId)
        assertThat(enqueued).hasSize(1)
        assertThat(enqueued.single().status).isEqualTo("pending")

        // An idempotent replay heals a run left stuck by an earlier Stop: it still answers and never duplicates.
        val replay = cancel(workflowId, attemptId, revision)

        assertThat(replay.statusCode).isEqualTo(HttpStatus.OK)
        assertThat(data(replay.body)["idempotent"]).isEqualTo(true)
        assertThat(data(replay.body)["killAcknowledged"]).isEqualTo(false)
        assertThat(continuations(attemptId)).hasSize(1)
    }

    @Test
    fun `a caller who is not the run owner gets 403 ACTION_NOT_ALLOWED and nothing is written`() {
        val revision = attempts.find(scope, namespace, foreignWorkflowId, "step-1", foreignAttemptId)!!.revision

        val refused = cancel(foreignWorkflowId, foreignAttemptId, revision)

        assertThat(refused.statusCode).withFailMessage { "cancel answered ${refused.statusCode}: ${refused.body}" }.isEqualTo(HttpStatus.FORBIDDEN)
        assertThat(error(refused.body)["code"]).isEqualTo("ACTION_NOT_ALLOWED")
        assertThat(attempts.find(scope, namespace, foreignWorkflowId, "step-1", foreignAttemptId)!!.status)
            .isEqualTo(AgentAttemptStatus.RUNNING)
        assertThat(continuations(foreignAttemptId)).isEmpty()
    }
}
