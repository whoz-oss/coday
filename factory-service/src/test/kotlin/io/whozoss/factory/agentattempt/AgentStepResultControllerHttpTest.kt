package io.whozoss.factory.agentattempt

import io.whozoss.factory.Neo4jDomainIntegrationTest
import io.whozoss.factory.agentattempt.domain.AgentStepAttemptRecord
import io.whozoss.factory.agentattempt.domain.AgentStepResultCapabilityIdentity
import io.whozoss.factory.agentattempt.persistence.AgentStepAttemptRepository
import io.whozoss.factory.agentattempt.service.AgentStepResultService
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
 * HTTP integration tests of
 * [io.whozoss.factory.agentattempt.web.AgentStepResultController] against the
 * embedded Neo4j engine (in-process harness, no Docker).
 *
 * Exercises `POST /api/factory/agent-step-results`, asserting the canonical
 * `{ "data": ... }` success envelope and the `{ "error": { code, ... } }`
 * failure envelope, together with the exact Node status-code contract. The
 * trust context is the loopback-dev principal (`org-local-dev` / `ws-default`).
 */
class AgentStepResultControllerHttpTest : Neo4jDomainIntegrationTest() {

    @Autowired
    private lateinit var restTemplate: TestRestTemplate

    @Autowired
    private lateinit var service: AgentStepResultService

    @Autowired
    private lateinit var attempts: AgentStepAttemptRepository

    private val namespace = "ns-http-a7"
    private val workflow = "wf-http-a7"
    private val step = "step-http-a7"
    private val caseId = "case-http-a7"
    private val agentName = "Agent"
    private val briefHash = "sha256:${"b".repeat(64)}"

    @BeforeEach
    fun seedAttempt() {
        attempts.insert(
            scope,
            AgentStepAttemptRecord(
                namespaceId = namespace,
                workflowId = workflow,
                stepId = step,
                attemptId = "attempt-http-1",
                agentId = "agent-1",
                status = "running",
                revision = 1,
                payload = "{}",
            ),
        )
    }

    @Test
    fun `creates a result and returns the canonical data envelope`() {
        val token = issueToken("attempt-http-1")

        val response = post(body("attempt-http-1"), token)

        assertThat(response.statusCode).isEqualTo(HttpStatus.CREATED)
        val data = data(response.body)!!
        assertThat(data["idempotent"]).isEqualTo(false)
        assertThat(data["resultId"]).isNotNull
        assertThat(data["resultHash"] as String).startsWith("sha256:")
    }

    @Test
    fun `an identical replay returns 200 with the same result id`() {
        val token = issueToken("attempt-http-1")
        val first = post(body("attempt-http-1"), token)

        val replay = post(body("attempt-http-1"), token)

        assertThat(first.statusCode).isEqualTo(HttpStatus.CREATED)
        assertThat(replay.statusCode).isEqualTo(HttpStatus.OK)
        assertThat(data(replay.body)!!["idempotent"]).isEqualTo(true)
        assertThat(data(replay.body)!!["resultId"]).isEqualTo(data(first.body)!!["resultId"])
    }

    @Test
    fun `a divergent result for the same attempt yields 409 RESULT_SEMANTIC_COLLISION`() {
        val token = issueToken("attempt-http-1")
        post(body("attempt-http-1"), token)

        val response = post(body("attempt-http-1", summary = "divergent"), token)

        assertThat(response.statusCode).isEqualTo(HttpStatus.CONFLICT)
        assertThat(errorCode(response.body)).isEqualTo("RESULT_SEMANTIC_COLLISION")
    }

    @Test
    fun `an unknown capability token yields 401 RESULT_CAPABILITY_INVALID`() {
        val response = post(body("attempt-http-1"), "unknown-token-0000000000000000000000000")

        assertThat(response.statusCode).isEqualTo(HttpStatus.UNAUTHORIZED)
        assertThat(errorCode(response.body)).isEqualTo("RESULT_CAPABILITY_INVALID")
    }

    @Test
    fun `a missing bearer token yields 401`() {
        val response = post(body("attempt-http-1"), null)

        assertThat(response.statusCode).isEqualTo(HttpStatus.UNAUTHORIZED)
    }

    @Test
    fun `an invalid business schema yields 400 RESULT_SCHEMA_INVALID`() {
        val token = issueToken("attempt-http-1")

        val response = post(body("attempt-http-1", summary = ""), token)

        assertThat(response.statusCode).isEqualTo(HttpStatus.BAD_REQUEST)
        assertThat(errorCode(response.body)).isEqualTo("RESULT_SCHEMA_INVALID")
    }

    @Test
    fun `a mismatched observed case id yields 400 RESULT_IDENTITY_MISMATCH`() {
        val token = issueToken("attempt-http-1")

        val response = post(body("attempt-http-1"), token, caseId = "wrong-case")

        assertThat(response.statusCode).isEqualTo(HttpStatus.BAD_REQUEST)
        assertThat(errorCode(response.body)).isEqualTo("RESULT_IDENTITY_MISMATCH")
    }

    @Test
    fun `a missing attempt id yields 400 INVALID_RESULT_REQUEST`() {
        val token = issueToken("attempt-http-1")

        val response = post(
            """{"result":{"status":"PASS","summary":"ok","claims":{"modifiedFiles":[]}}}""",
            token,
        )

        assertThat(response.statusCode).isEqualTo(HttpStatus.BAD_REQUEST)
        assertThat(errorCode(response.body)).isEqualTo("INVALID_RESULT_REQUEST")
    }

    @Test
    fun `an idempotency key caches the response and rejects a divergent replay`() {
        val token = issueToken("attempt-http-1")
        val first = post(body("attempt-http-1"), token, idempotencyKey = "http-key-1")

        val replay = post(body("attempt-http-1"), token, idempotencyKey = "http-key-1")

        val collision = post(body("attempt-http-1", summary = "divergent"), token, idempotencyKey = "http-key-1")

        assertThat(first.statusCode).isEqualTo(HttpStatus.CREATED)
        assertThat(replay.statusCode).isEqualTo(HttpStatus.OK)
        assertThat(data(replay.body)!!["idempotent"]).isEqualTo(true)
        assertThat(collision.statusCode).isEqualTo(HttpStatus.CONFLICT)
        assertThat(errorCode(collision.body)).isEqualTo("IDEMPOTENCY_KEY_COLLISION")
    }

    // ------------------------------------------------------------------
    // Fixtures
    // ------------------------------------------------------------------

    private fun issueToken(attemptId: String): String =
        service.issue(
            scope,
            AgentStepResultCapabilityIdentity(
                attemptId = attemptId,
                workflowId = workflow,
                stepId = step,
                namespaceId = namespace,
                caseId = caseId,
                agentName = agentName,
                briefHash = briefHash,
            ),
        ).token

    private fun body(attemptId: String, summary: String = "ok", status: String = "PASS"): String =
        """{"attemptId":"$attemptId","result":{"status":"$status","summary":"$summary",
            "claims":{"modifiedFiles":[]}}}"""

    private fun post(
        body: String,
        token: String?,
        caseId: String? = this.caseId,
        agentName: String? = this.agentName,
        idempotencyKey: String? = null,
    ): ResponseEntity<Map<String, Any?>> {
        val headers = HttpHeaders().apply {
            contentType = MediaType.APPLICATION_JSON
            if (token != null) set("Authorization", "Bearer $token")
            if (caseId != null) set("X-AgentOS-Case-Id", caseId)
            if (agentName != null) set("X-AgentOS-Agent-Name", agentName)
            if (idempotencyKey != null) set("X-Idempotency-Key", idempotencyKey)
        }
        return restTemplate.exchange(
            "/api/factory/agent-step-results",
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
