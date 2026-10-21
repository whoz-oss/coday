package io.whozoss.factory.agentattempt

import io.whozoss.factory.Neo4jDomainIntegrationTest
import io.whozoss.factory.agentattempt.domain.AgentAttemptStatus
import io.whozoss.factory.agentattempt.domain.AgentStepAttemptRecord
import io.whozoss.factory.agentattempt.domain.AgentStepResultCapabilityIdentity
import io.whozoss.factory.agentattempt.domain.CanonicalJsonHash
import io.whozoss.factory.agentattempt.domain.DurableAgentAttempt
import io.whozoss.factory.agentattempt.persistence.AgentStepAttemptRepository
import io.whozoss.factory.agentattempt.service.AgentStepResultService
import io.whozoss.factory.agentattempt.service.DurableAgentAttemptService
import io.whozoss.factory.persistence.TenantScope
import io.whozoss.factory.workstream.WorkstreamService
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
 * Exercises `POST /api/factory/agent-step-results` (`{ attemptId, result }` plus a
 * required `Idempotency-Key` header). The shared integration profile runs with
 * `factory.security.mode=local`, so these calls carry no bearer and act as
 * the fixed local test user (`org-local-dev` / `ws-default`). The attempt is resolved
 * by its id within that user's ORGANIZATION and the submission runs under the
 * attempt's OWN persisted scope; the secure-mode behaviour lives in
 * [AgentStepResultControllerSecureModeHttpTest].
 */
class AgentStepResultControllerHttpTest : Neo4jDomainIntegrationTest() {

    @Autowired
    private lateinit var restTemplate: TestRestTemplate

    @Autowired
    private lateinit var service: AgentStepResultService

    @Autowired
    private lateinit var attempts: AgentStepAttemptRepository

    @Autowired
    private lateinit var durableAttempts: DurableAgentAttemptService

    @Autowired
    private lateinit var workstreamService: WorkstreamService

    private val namespace = "ns-http-a7"
    private val workflow = "wf-http-a7"
    private val step = "step-http-a7"
    private val caseId = "case-http-a7"
    private val agentName = "Agent"
    private val frozenBrief = "frozen-http-api-brief"
    private val briefHash = CanonicalJsonHash.sha256(frozenBrief)
    private val attemptId = "attempt-http-1"

    @BeforeEach
    fun seedDefaultAttempt() {
        registerAttempt(scope, attemptId, namespace)
    }

    // ------------------------------------------------------------------
    // Contract
    // ------------------------------------------------------------------

    @Test
    fun `creates a result and returns the canonical data envelope`() {
        val response = post(body(attemptId), key = "key-create")

        assertThat(response.statusCode).isEqualTo(HttpStatus.CREATED)
        val data = data(response.body)!!
        assertThat(data["idempotent"]).isEqualTo(false)
        assertThat(data["resultId"]).isNotNull
        assertThat(data["resultHash"] as String).startsWith("sha256:")
    }

    @Test
    fun `an identical replay with the same idempotency key returns 200 with the same result id`() {
        val first = post(body(attemptId), key = "key-replay")

        val replay = post(body(attemptId), key = "key-replay")

        assertThat(first.statusCode).isEqualTo(HttpStatus.CREATED)
        assertThat(replay.statusCode).isEqualTo(HttpStatus.OK)
        assertThat(data(replay.body)!!["idempotent"]).isEqualTo(true)
        assertThat(data(replay.body)!!["resultId"]).isEqualTo(data(first.body)!!["resultId"])
    }

    @Test
    fun `a divergent payload with the same idempotency key yields 409 IDEMPOTENCY_KEY_COLLISION`() {
        post(body(attemptId), key = "key-collision")

        val response = post(body(attemptId, summary = "divergent"), key = "key-collision")

        assertThat(response.statusCode).isEqualTo(HttpStatus.CONFLICT)
        assertThat(errorCode(response.body)).isEqualTo("IDEMPOTENCY_KEY_COLLISION")
    }

    @Test
    fun `a divergent payload with a different idempotency key yields 409 RESULT_SEMANTIC_COLLISION`() {
        post(body(attemptId), key = "key-first")

        val response = post(body(attemptId, summary = "divergent"), key = "key-second")

        assertThat(response.statusCode).isEqualTo(HttpStatus.CONFLICT)
        assertThat(errorCode(response.body)).isEqualTo("RESULT_SEMANTIC_COLLISION")
    }

    @Test
    fun `an extra body field yields 400 INVALID_RESULT_REQUEST`() {
        val response = post(
            """{"attemptId":"$attemptId","caseId":"forged-case","result":$RESULT_OK}""",
            key = "key-extra",
        )

        assertThat(response.statusCode).isEqualTo(HttpStatus.BAD_REQUEST)
        assertThat(errorCode(response.body)).isEqualTo("INVALID_RESULT_REQUEST")
    }

    @Test
    fun `a missing attempt id yields 400 INVALID_RESULT_REQUEST`() {
        val response = post("""{"result":$RESULT_OK}""", key = "key-no-attempt")

        assertThat(response.statusCode).isEqualTo(HttpStatus.BAD_REQUEST)
        assertThat(errorCode(response.body)).isEqualTo("INVALID_RESULT_REQUEST")
    }

    @Test
    fun `a missing idempotency key yields 400 INVALID_RESULT_REQUEST`() {
        val response = post(body(attemptId), key = null)

        assertThat(response.statusCode).isEqualTo(HttpStatus.BAD_REQUEST)
        assertThat(errorCode(response.body)).isEqualTo("INVALID_RESULT_REQUEST")
    }

    @Test
    fun `an empty summary yields 400 RESULT_SCHEMA_INVALID`() {
        val response = post(body(attemptId, summary = ""), key = "key-schema")

        assertThat(response.statusCode).isEqualTo(HttpStatus.BAD_REQUEST)
        assertThat(errorCode(response.body)).isEqualTo("RESULT_SCHEMA_INVALID")
    }

    @Test
    fun `an unknown attempt id yields 404`() {
        val response = post(body("attempt-http-unknown"), key = "key-unknown")

        assertThat(response.statusCode).isEqualTo(HttpStatus.NOT_FOUND)
    }

    @Test
    fun `a submission persists and round trips delivery analysis and artifacts`() {
        val payload = """{"attemptId":"$attemptId","result":{"status":"PASS","summary":"done","claims":{"modifiedFiles":[],"deliveryAnalysis":{"baseBranch":"sprint","changeType":"bugfix","ticket":"WZ-33157","title":"Complete RAWeb 5.4 accessible table names"}},"artifacts":[{"kind":"run-brief","encoding":"markdown","content":"# Run brief"}],"findings":[{"severity":"blocking","code":"A11Y","summary":"Needs verification"}]}}"""

        val response = post(payload, key = "key-delivery")

        assertThat(response.statusCode).isEqualTo(HttpStatus.CREATED)
        val stored = service.acceptedResult(scope, namespace, workflow, step, attemptId)!!
        assertThat(stored.claims.modifiedFiles).isEmpty()
        assertThat(stored.claims.deliveryAnalysis?.baseBranch).isEqualTo("sprint")
        assertThat(stored.claims.deliveryAnalysis?.changeType).isEqualTo("bugfix")
        assertThat(stored.claims.deliveryAnalysis?.ticket).isEqualTo("WZ-33157")
        assertThat(stored.claims.deliveryAnalysis?.title).isEqualTo("Complete RAWeb 5.4 accessible table names")
        assertThat(stored.artifacts.single().kind).isEqualTo("run-brief")
    }

    // ------------------------------------------------------------------
    // Scope: the attempt's own persisted scope, organization fencing
    // ------------------------------------------------------------------

    @Test
    fun `an attempt stored under the workstream mapped from its namespace is submitted under that scope`() {
        // WorkflowController.createRun stores a run under the workstream mapped from
        // its namespace (namespace-<uuid>), NOT under the caller's own ws-default scope.
        val namespaceUuid = "0d4bd471-df37-43d8-a8f7-c989f95e71d7"
        val mappedWorkstream = workstreamService.findOrInitializeForNamespace(scope.organizationId, namespaceUuid)
        val mappedScope = TenantScope(scope.organizationId, mappedWorkstream.workstreamId)
        assertThat(mappedScope.workstreamId).isEqualTo("namespace-$namespaceUuid")
        assertThat(mappedScope).isNotEqualTo(scope)
        val mappedAttempt = "attempt-http-mapped"
        registerAttempt(mappedScope, mappedAttempt, namespaceUuid)

        // No bearer: the caller is the local test user of ws-default, a different workstream.
        val response = post(body(mappedAttempt), key = "key-mapped")

        assertThat(response.statusCode).isEqualTo(HttpStatus.CREATED)
        assertThat(data(response.body)!!["idempotent"]).isEqualTo(false)
        assertThat(service.acceptedResult(mappedScope, namespaceUuid, workflow, step, mappedAttempt)).isNotNull
        // The result is not stored under the caller's own scope.
        assertThat(service.acceptedResult(scope, namespaceUuid, workflow, step, mappedAttempt)).isNull()
    }

    @Test
    fun `an invalid static bearer in local mode still creates the result`() {
        val response = post(body(attemptId), key = "key-static-bearer", bearer = "some-old-static-token")

        assertThat(response.statusCode).isEqualTo(HttpStatus.CREATED)
        assertThat(data(response.body)!!["idempotent"]).isEqualTo(false)
    }

    @Test
    fun `an attempt of another organization is not found`() {
        val otherScope = TenantScope("org-other", "ws-other")
        val foreignAttempt = "attempt-http-foreign-org"
        registerAttempt(otherScope, foreignAttempt, namespace)

        val response = post(body(foreignAttempt), key = "key-foreign-org")

        assertThat(response.statusCode).isEqualTo(HttpStatus.NOT_FOUND)
        assertThat(service.acceptedResult(otherScope, namespace, workflow, step, foreignAttempt)).isNull()
    }

    // ------------------------------------------------------------------
    // Fixtures
    // ------------------------------------------------------------------

    /**
     * Registers a durable attempt under [target] with its frozen brief, together with
     * the legacy attempt record and the capability token `submitTrusted` redeems.
     */
    private fun registerAttempt(target: TenantScope, id: String, namespaceId: String) {
        attempts.insert(
            target,
            AgentStepAttemptRecord(
                namespaceId = namespaceId,
                workflowId = workflow,
                stepId = step,
                attemptId = id,
                agentId = "agent-1",
                status = "running",
                revision = 1,
                payload = "{}",
            ),
        )
        val token = service.issue(
            target,
            AgentStepResultCapabilityIdentity(
                attemptId = id,
                workflowId = workflow,
                stepId = step,
                namespaceId = namespaceId,
                caseId = caseId,
                agentName = agentName,
                briefHash = briefHash,
            ),
        ).token
        durableAttempts.register(
            target,
            DurableAgentAttempt(
                attemptId = id,
                caseId = caseId,
                namespaceId = namespaceId,
                workflowId = workflow,
                stepId = step,
                attemptNumber = 1,
                agentName = agentName,
                capabilityToken = token,
                status = AgentAttemptStatus.RUNNING,
                brief = frozenBrief,
            ),
        )
    }

    private fun body(id: String, summary: String = "ok"): String =
        """{"attemptId":"$id","result":{"status":"PASS","summary":"$summary","claims":{"modifiedFiles":[]}}}"""

    private fun post(body: String, key: String?, bearer: String? = null): ResponseEntity<Map<String, Any?>> {
        val headers = HttpHeaders().apply {
            contentType = MediaType.APPLICATION_JSON
            if (key != null) set("Idempotency-Key", key)
            if (bearer != null) setBearerAuth(bearer)
        }
        return restTemplate.exchange(
            "/api/factory/agent-step-results",
            HttpMethod.POST,
            HttpEntity(body, headers),
            object : ParameterizedTypeReference<Map<String, Any?>>() {},
        )
    }

    @Suppress("UNCHECKED_CAST")
    private fun data(body: Map<String, Any?>?): Map<String, Any?>? = body?.get("data") as? Map<String, Any?>

    @Suppress("UNCHECKED_CAST")
    private fun errorCode(body: Map<String, Any?>?): String? =
        (body?.get("error") as? Map<String, Any?>)?.get("code") as? String

    private companion object {
        const val RESULT_OK = """{"status":"PASS","summary":"ok","claims":{"modifiedFiles":[]}}"""
    }
}
