package io.whozoss.factory.agentattempt

import io.whozoss.factory.Neo4jDomainIntegrationTest
import io.whozoss.factory.agentattempt.domain.AgentAttemptStatus
import io.whozoss.factory.agentattempt.domain.AgentStepAttemptRecord
import io.whozoss.factory.agentattempt.domain.AgentStepResultCapabilityIdentity
import io.whozoss.factory.agentattempt.domain.DurableAgentAttempt
import io.whozoss.factory.agentattempt.persistence.AgentStepAttemptRepository
import io.whozoss.factory.agentattempt.service.AgentStepResultService
import io.whozoss.factory.agentattempt.service.DurableAgentAttemptService
import io.whozoss.factory.capability.CapabilityExecutionService
import io.whozoss.factory.workflow.persistence.HumanInteractionRepository
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

/**
 * HTTP integration tests of the Phase 4 ask-step-question channel:
 * `POST /api/factory/agent-step-questions` (worker ask) and
 * `POST /api/factory/workflows/{workflowId}/agent-step-questions/{interactionId}/answer`
 * (audited human answer), against the embedded Neo4j engine.
 *
 * Asserts the canonical `{ "data": ... }` / `{ "error": { code } }` envelopes
 * and the status-code contract. The trust context is the loopback-dev
 * principal (`org-local-dev` / `ws-default`).
 */
class AgentStepQuestionControllerHttpTest : Neo4jDomainIntegrationTest() {

    @Autowired
    private lateinit var restTemplate: TestRestTemplate

    @Autowired
    private lateinit var resultService: AgentStepResultService

    @Autowired
    private lateinit var attemptService: DurableAgentAttemptService

    @Autowired
    private lateinit var legacyAttempts: AgentStepAttemptRepository

    @Autowired
    private lateinit var interactions: HumanInteractionRepository

    private val namespace = "ns-q-http"
    private val workflow = "wf-q-http"
    private val step = "step-q-http"
    private val caseId = "case-q-http"
    private val agentName = "Worker"
    private val attemptId = "attempt-q-http"

    @BeforeEach
    fun seed() {
        seedRunningAttempt()
    }

    @Test
    fun `a worker question returns 202 with the interaction link and parks the attempt`() {
        val token = issueToken()

        val response = postQuestion(questionBody(), token)

        assertThat(response.statusCode).isEqualTo(HttpStatus.ACCEPTED)
        val data = data(response.body)!!
        assertThat(data["attemptId"]).isEqualTo(attemptId)
        assertThat(data["status"]).isEqualTo("waiting_human")
        assertThat(data["idempotent"]).isEqualTo(false)
        assertThat(data["interactionId"] as String).isNotBlank()
        assertThat(attemptService.find(scope, namespace, workflow, step, attemptId)!!.status)
            .isEqualTo(AgentAttemptStatus.WAITING_HUMAN)
    }

    @Test
    fun `an identical re-ask is an idempotent 202 replay on the same interaction`() {
        val token = issueToken()
        val first = postQuestion(questionBody(), token)

        val replay = postQuestion(questionBody(), token)

        assertThat(first.statusCode).isEqualTo(HttpStatus.ACCEPTED)
        assertThat(replay.statusCode).isEqualTo(HttpStatus.ACCEPTED)
        assertThat(data(replay.body)!!["idempotent"]).isEqualTo(true)
        assertThat(data(replay.body)!!["interactionId"]).isEqualTo(data(first.body)!!["interactionId"])
    }

    @Test
    fun `an unknown capability token yields 401 RESULT_CAPABILITY_INVALID`() {
        val response = postQuestion(questionBody(), "unknown-token-0000000000000000000000000")

        assertThat(response.statusCode).isEqualTo(HttpStatus.UNAUTHORIZED)
        assertThat(errorCode(response.body)).isEqualTo("RESULT_CAPABILITY_INVALID")
    }

    @Test
    fun `an invalid question schema yields 400 QUESTION_SCHEMA_INVALID`() {
        val token = issueToken()

        val response = postQuestion("""{"attemptId":"$attemptId","question":{"prompt":"","type":"FREE_TEXT","contextHash":"h"}}""", token)

        assertThat(response.statusCode).isEqualTo(HttpStatus.BAD_REQUEST)
        assertThat(errorCode(response.body)).isEqualTo("QUESTION_SCHEMA_INVALID")
    }

    @Test
    fun `a declared attempt diverging from the capability yields 400 RESULT_IDENTITY_MISMATCH`() {
        val token = issueToken()

        val response = postQuestion(questionBody(attemptId = "other-attempt"), token)

        assertThat(response.statusCode).isEqualTo(HttpStatus.BAD_REQUEST)
        assertThat(errorCode(response.body)).isEqualTo("RESULT_IDENTITY_MISMATCH")
    }

    @Test
    fun `a trusted namespace diverging from the capability yields 400 RESULT_IDENTITY_MISMATCH`() {
        val token = issueToken()

        val response = postQuestion(questionBody(), token, trustedNamespace = "other-namespace")

        assertThat(response.statusCode).isEqualTo(HttpStatus.BAD_REQUEST)
        assertThat(errorCode(response.body)).isEqualTo("RESULT_IDENTITY_MISMATCH")
    }

    @Test
    fun `a missing attempt id or bearer token yields a 400 or 401`() {
        val token = issueToken()

        val noAttempt = postQuestion("""{"question":{"prompt":"P","type":"FREE_TEXT","contextHash":"h"}}""", token)
        assertThat(noAttempt.statusCode).isEqualTo(HttpStatus.BAD_REQUEST)
        assertThat(errorCode(noAttempt.body)).isEqualTo("INVALID_RESULT_REQUEST")

        val noToken = postQuestion(questionBody(), null)
        assertThat(noToken.statusCode).isEqualTo(HttpStatus.UNAUTHORIZED)
    }

    @Test
    fun `the human answer closes the question, supersedes attempt N and exposes the successor`() {
        val token = issueToken()
        val asked = postQuestion(questionBody(), token)
        val interactionId = data(asked.body)!!["interactionId"] as String
        val revision = interactions.find(scope, namespace, workflow, interactionId)!!.revision

        val answered = postAnswer(interactionId, """{"namespaceId":"$namespace","expectedRevision":$revision,"answer":"go ahead"}""")

        assertThat(answered.statusCode).isEqualTo(HttpStatus.OK)
        val data = data(answered.body)!!
        val successorId = CapabilityExecutionService.retryAttemptId(workflow, step, 2)
        assertThat(data["status"]).isEqualTo("closed")
        assertThat(data["supersededAttemptId"]).isEqualTo(attemptId)
        assertThat(data["successorAttemptId"]).isEqualTo(successorId)
        assertThat(data["successorAttemptNumber"]).isEqualTo(2)
        assertThat(data["actorId"]).isEqualTo("alice")

        // A second answer is a 409 QUESTION_ALREADY_ANSWERED — no attempt N+2.
        val replay = postAnswer(interactionId, """{"namespaceId":"$namespace","expectedRevision":${revision + 1},"answer":"again"}""")
        assertThat(replay.statusCode).isEqualTo(HttpStatus.CONFLICT)
        assertThat(errorCode(replay.body)).isEqualTo("QUESTION_ALREADY_ANSWERED")
        assertThat(attemptService.findByWorkflow(scope, namespace, workflow)).hasSize(2)
    }

    @Test
    fun `a stale revision on the human answer yields 409 REVISION_CONFLICT`() {
        val token = issueToken()
        val asked = postQuestion(questionBody(), token)
        val interactionId = data(asked.body)!!["interactionId"] as String

        val response = postAnswer(interactionId, """{"namespaceId":"$namespace","expectedRevision":999,"answer":"go ahead"}""")

        assertThat(response.statusCode).isEqualTo(HttpStatus.CONFLICT)
        assertThat(errorCode(response.body)).isEqualTo("REVISION_CONFLICT")
    }

    @Test
    fun `an unknown question interaction yields 404`() {
        val response = postAnswer(
            "00000000-0000-0000-0000-000000000000",
            """{"namespaceId":"$namespace","expectedRevision":1,"answer":"go ahead"}""",
        )

        assertThat(response.statusCode).isEqualTo(HttpStatus.NOT_FOUND)
        assertThat(errorCode(response.body)).isEqualTo("QUESTION_INTERACTION_NOT_FOUND")
    }

    // ------------------------------------------------------------------
    // Fixtures
    // ------------------------------------------------------------------

    private fun seedRunningAttempt() {
        legacyAttempts.insert(
            scope,
            AgentStepAttemptRecord(namespace, workflow, step, attemptId, "agent-1", "running", 1, "{}"),
        )
        attemptService.register(
            scope,
            DurableAgentAttempt(
                attemptId = attemptId,
                caseId = caseId,
                namespaceId = namespace,
                workflowId = workflow,
                stepId = step,
                attemptNumber = 1,
                agentName = agentName,
            ),
        )
        attemptService.claim(scope, namespace, workflow, step, attemptId, "owner-1", leaseTtlMs = 60_000)
        attemptService.transition(scope, namespace, workflow, step, attemptId, "owner-1", AgentAttemptStatus.STARTING)
        attemptService.transition(scope, namespace, workflow, step, attemptId, "owner-1", AgentAttemptStatus.RUNNING)
    }

    private fun issueToken(): String =
        resultService.issue(
            scope,
            AgentStepResultCapabilityIdentity(
                attemptId = attemptId,
                workflowId = workflow,
                stepId = step,
                namespaceId = namespace,
                caseId = caseId,
                agentName = agentName,
                briefHash = "sha256:${"f".repeat(64)}",
            ),
        ).token

    private fun questionBody(attemptId: String = this.attemptId): String =
        """{"attemptId":"$attemptId","question":{"prompt":"Proceed?","type":"FREE_TEXT","contextHash":"sha256:http-q1"}}"""

    private fun postQuestion(
        body: String,
        token: String?,
        trustedNamespace: String? = null,
    ): ResponseEntity<Map<String, Any?>> {
        val headers = HttpHeaders().apply {
            contentType = MediaType.APPLICATION_JSON
            if (token != null) set("Authorization", "Bearer $token")
            set("X-AgentOS-Case-Id", caseId)
            set("X-AgentOS-Agent-Name", agentName)
            if (trustedNamespace != null) set("x-factory-namespace-id", trustedNamespace)
        }
        return restTemplate.exchange(
            "/api/factory/agent-step-questions",
            HttpMethod.POST,
            HttpEntity(body, headers),
            jsonType(),
        )
    }

    private fun postAnswer(
        interactionId: String,
        body: String,
        actor: String? = "alice",
    ): ResponseEntity<Map<String, Any?>> {
        val headers = HttpHeaders().apply {
            contentType = MediaType.APPLICATION_JSON
            // Loopback-dev trust boundary: the verified human principal.
            if (actor != null) set("x-factory-actor-id", actor)
        }
        return restTemplate.exchange(
            "/api/factory/workflows/$workflow/agent-step-questions/$interactionId/answer",
            HttpMethod.POST,
            HttpEntity(body, headers),
            jsonType(),
        )
    }

    private fun jsonType(): ParameterizedTypeReference<Map<String, Any?>> =
        object : ParameterizedTypeReference<Map<String, Any?>>() {}

    @Suppress("UNCHECKED_CAST")
    private fun data(body: Map<String, Any?>?): Map<String, Any?>? = body?.get("data") as? Map<String, Any?>

    @Suppress("UNCHECKED_CAST")
    private fun errorCode(body: Map<String, Any?>?): String? =
        (body?.get("error") as? Map<String, Any?>)?.get("code") as? String
}
