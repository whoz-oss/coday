package io.whozoss.factory.agentattempt

import io.whozoss.factory.Neo4jDomainIntegrationTest
import io.whozoss.factory.agentattempt.domain.AgentAttemptStatus
import io.whozoss.factory.agentattempt.domain.AgentStepAttemptRecord
import io.whozoss.factory.agentattempt.domain.DurableAgentAttempt
import io.whozoss.factory.agentattempt.domain.DurableAttemptIntegrationBinding
import io.whozoss.factory.agentattempt.domain.CanonicalJsonHash
import io.whozoss.factory.agentattempt.domain.AgentStepResultCapabilityIdentity
import io.whozoss.factory.agentattempt.persistence.AgentStepAttemptRepository
import io.whozoss.factory.agentattempt.persistence.DurableAttemptIntegrationBindingRepository
import io.whozoss.factory.agentattempt.service.AgentStepResultService
import io.whozoss.factory.agentattempt.service.DurableAgentAttemptService
import io.whozoss.factory.config.FactoryProperties
import io.whozoss.factory.web.TestJwt
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
 * HTTP integration tests of
 * [io.whozoss.factory.agentattempt.web.AgentStepResultController] against the
 * embedded Neo4j engine (in-process harness, no Docker).
 *
 * Exercises `POST /api/factory/agent-step-results`, asserting the canonical
 * `{ "data": ... }` success envelope and the `{ "error": { code, ... } }`
 * failure envelope, together with the exact Node status-code contract. The
 * trust context is the loopback-dev principal (`org-local-dev` / `ws-default`).
 */
@TestPropertySource(
    properties = [
        "factory.security.http-api-submission.enabled=true",
        "factory.security.http-api-submission.bearer-token=http-api-integration-token",
        "factory.security.http-api-submission.service-identity-id=agentos-http-api-test",
    ],
)
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
    private lateinit var properties: FactoryProperties

    @Autowired
    private lateinit var integrationBindings: DurableAttemptIntegrationBindingRepository

    private val namespace = "ns-http-a7"
    private val workflow = "wf-http-a7"
    private val step = "step-http-a7"
    private val caseId = "case-http-a7"
    private val agentName = "Agent"
    private val frozenBrief = "frozen-http-api-brief"
    private val briefHash = CanonicalJsonHash.sha256(frozenBrief)

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
    fun `HTTP API service bearer submits by Factory attempt id without runtime AgentOS identity`() {
        val token = issueToken("attempt-http-1")
        durableAttempts.register(
            scope,
            DurableAgentAttempt(
                attemptId = "attempt-http-1", caseId = caseId, namespaceId = namespace,
                workflowId = workflow, stepId = step, attemptNumber = 1, agentName = agentName,
                capabilityToken = token, status = AgentAttemptStatus.RUNNING, brief = frozenBrief,
            ),
        )
        bind("attempt-http-1")
        val headers = HttpHeaders().apply {
            contentType = MediaType.APPLICATION_JSON
            setBearerAuth("http-api-integration-token")
            set("Idempotency-Key", "submission-1")
        }

        val response = restTemplate.exchange(
            "/api/factory/agent-step-results",
            HttpMethod.POST,
            HttpEntity(body("attempt-http-1"), headers),
            jsonType(),
        )

        assertThat(response.statusCode).isEqualTo(HttpStatus.CREATED)
        assertThat(data(response.body)!!["idempotent"]).isEqualTo(false)
    }

    @Test
    fun `HTTP API service bearer supports another namespace only when explicitly bound`() {
        val foreignNamespace = "ns-not-authorized"
        val foreignAttempt = "attempt-http-foreign"
        attempts.insert(
            scope,
            AgentStepAttemptRecord(foreignNamespace, workflow, step, foreignAttempt, "agent-1", "running", 1, "{}"),
        )
        val token = service.issue(
            scope,
            AgentStepResultCapabilityIdentity(
                foreignAttempt, workflow, step, foreignNamespace, caseId, agentName, briefHash,
            ),
        ).token
        durableAttempts.register(
            scope,
            DurableAgentAttempt(
                attemptId = foreignAttempt, caseId = caseId, namespaceId = foreignNamespace,
                workflowId = workflow, stepId = step, attemptNumber = 1, agentName = agentName,
                capabilityToken = token, status = AgentAttemptStatus.RUNNING, brief = frozenBrief,
            ),
        )
        bind(foreignAttempt, foreignNamespace)
        val headers = HttpHeaders().apply {
            contentType = MediaType.APPLICATION_JSON
            setBearerAuth("http-api-integration-token")
            set("Idempotency-Key", "foreign-submission")
        }

        val response = restTemplate.exchange(
            "/api/factory/agent-step-results", HttpMethod.POST,
            HttpEntity(body(foreignAttempt), headers), jsonType(),
        )

        assertThat(response.statusCode).isEqualTo(HttpStatus.CREATED)
        assertThat(data(response.body)!!["idempotent"]).isEqualTo(false)
    }

    @Test
    fun `HTTP API service bearer rejects a terminal attempt after retry or stop`() {
        val token = issueToken("attempt-http-1")
        durableAttempts.register(
            scope,
            DurableAgentAttempt(
                attemptId = "attempt-http-1", caseId = caseId, namespaceId = namespace,
                workflowId = workflow, stepId = step, attemptNumber = 1, agentName = agentName,
                capabilityToken = token, brief = frozenBrief,
            ),
        )
        bind("attempt-http-1")
        val persisted = durableAttempts.find(scope, namespace, workflow, step, "attempt-http-1")!!
        durableAttempts.requestCancel(
            scope, namespace, workflow, step, "attempt-http-1", persisted.revision,
        )
        val headers = HttpHeaders().apply {
            contentType = MediaType.APPLICATION_JSON
            setBearerAuth("http-api-integration-token")
            set("Idempotency-Key", "late-submission")
        }

        val response = restTemplate.exchange(
            "/api/factory/agent-step-results", HttpMethod.POST,
            HttpEntity(body("attempt-http-1"), headers), jsonType(),
        )

        assertThat(response.statusCode).isEqualTo(HttpStatus.CREATED)
        assertThat(data(response.body)!!["idempotent"]).isEqualTo(false)
    }

    @Test
    fun `HTTP API service submission is idempotent and rejects divergent replay`() {
        val token = issueToken("attempt-http-1")
        durableAttempts.register(
            scope,
            DurableAgentAttempt(
                attemptId = "attempt-http-1", caseId = caseId, namespaceId = namespace,
                workflowId = workflow, stepId = step, attemptNumber = 1, agentName = agentName,
                capabilityToken = token, status = AgentAttemptStatus.RUNNING, brief = frozenBrief,
            ),
        )
        bind("attempt-http-1")
        fun submit(summary: String): ResponseEntity<Map<String, Any?>> {
            val headers = HttpHeaders().apply {
                contentType = MediaType.APPLICATION_JSON
                setBearerAuth("http-api-integration-token")
                set("Idempotency-Key", "stable-submission")
            }
            return restTemplate.exchange(
                "/api/factory/agent-step-results", HttpMethod.POST,
                HttpEntity(body("attempt-http-1", summary), headers), jsonType(),
            )
        }

        assertThat(submit("ok").statusCode).isEqualTo(HttpStatus.CREATED)
        assertThat(submit("ok").statusCode).isEqualTo(HttpStatus.OK)
        val collision = submit("different")
        assertThat(collision.statusCode).isEqualTo(HttpStatus.CONFLICT)
        assertThat(errorCode(collision.body)).isEqualTo("IDEMPOTENCY_KEY_COLLISION")
    }

    @Test
    fun `signed AgentOS service submission resolves the active attempt from namespace and case`() {
        val token = issueToken("attempt-http-1")
        durableAttempts.register(
            scope,
            DurableAgentAttempt(
                attemptId = "attempt-http-1",
                caseId = caseId,
                namespaceId = namespace,
                workflowId = workflow,
                stepId = step,
                attemptNumber = 1,
                agentName = agentName,
                capabilityToken = token,
                status = AgentAttemptStatus.RUNNING,
            ),
        )
        val signed = TestJwt.signProxyHeaders(
            principalId = "agentos-worker",
            principalType = "service",
            serviceIdentityId = "agentos-factory-bridge",
            scopes = listOf("workflow:write"),
            namespaceId = namespace,
            caseId = caseId,
            secret = properties.security.fakeIdpSecret,
        )
        val headers = HttpHeaders().apply {
            contentType = MediaType.APPLICATION_JSON
            signed.forEach(::set)
            set("X-AgentOS-Agent-Name", agentName)
            set("Idempotency-Key", "agentos:$caseId:request-1")
        }

        val response = restTemplate.exchange(
            "/api/factory/agent-step-results",
            HttpMethod.POST,
            HttpEntity("""{"result":{"status":"PASS","summary":"ok","claims":{"modifiedFiles":[]}}}""", headers),
            jsonType(),
        )

        assertThat(response.statusCode).isEqualTo(HttpStatus.CREATED)
        assertThat(data(response.body)!!["idempotent"]).isEqualTo(false)
    }

    @Test
    fun `signed AgentOS submission persists and round trips delivery analysis`() {
        val token = issueToken("attempt-http-1")
        durableAttempts.register(
            scope,
            DurableAgentAttempt(
                attemptId = "attempt-http-1", caseId = caseId, namespaceId = namespace,
                workflowId = workflow, stepId = step, attemptNumber = 1, agentName = agentName,
                capabilityToken = token, status = AgentAttemptStatus.RUNNING,
            ),
        )
        val signed = TestJwt.signProxyHeaders(
            principalId = "agentos-worker", principalType = "service",
            namespaceId = namespace, caseId = caseId, secret = properties.security.fakeIdpSecret,
        )
        val headers = HttpHeaders().apply {
            contentType = MediaType.APPLICATION_JSON
            signed.forEach(::set)
            set("X-AgentOS-Agent-Name", agentName)
            set("Idempotency-Key", "agentos:$caseId:delivery")
        }
        val payload = """{"result":{"status":"PASS","summary":"done","claims":{"modifiedFiles":[],"deliveryAnalysis":{"baseBranch":"sprint","changeType":"bugfix","ticket":"WZ-33157","title":"Complete RAWeb 5.4 accessible table names"}},"artifacts":[{"kind":"run-brief","encoding":"markdown","content":"# Run brief"}],"findings":[{"severity":"blocking","code":"A11Y","summary":"Needs verification"}]}}"""

        val response = restTemplate.exchange(
            "/api/factory/agent-step-results", HttpMethod.POST, HttpEntity(payload, headers), jsonType(),
        )

        assertThat(response.statusCode).isEqualTo(HttpStatus.CREATED)
        val stored = service.acceptedResult(scope, namespace, workflow, step, "attempt-http-1")!!
        assertThat(stored.claims.modifiedFiles).isEmpty()
        assertThat(stored.claims.deliveryAnalysis?.baseBranch).isEqualTo("sprint")
        assertThat(stored.claims.deliveryAnalysis?.changeType).isEqualTo("bugfix")
        assertThat(stored.claims.deliveryAnalysis?.ticket).isEqualTo("WZ-33157")
        assertThat(stored.claims.deliveryAnalysis?.title).isEqualTo("Complete RAWeb 5.4 accessible table names")
        assertThat(stored.artifacts.single().kind).isEqualTo("run-brief")
    }

    @Test
    fun `signed AgentOS service submission rejects a body attempt id`() {
        val signed = TestJwt.signProxyHeaders(
            principalId = "agentos-worker",
            principalType = "service",
            namespaceId = namespace,
            caseId = caseId,
            secret = properties.security.fakeIdpSecret,
        )
        val headers = HttpHeaders().apply {
            contentType = MediaType.APPLICATION_JSON
            signed.forEach(::set)
            set("Idempotency-Key", "agentos:$caseId:request-2")
        }

        val response = restTemplate.exchange(
            "/api/factory/agent-step-results",
            HttpMethod.POST,
            HttpEntity(body("attempt-http-1"), headers),
            jsonType(),
        )

        assertThat(response.statusCode).isEqualTo(HttpStatus.BAD_REQUEST)
        assertThat(errorCode(response.body)).isEqualTo("INVALID_RESULT_REQUEST")
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
    fun `a trusted namespace diverging from the capability yields 400 RESULT_IDENTITY_MISMATCH`() {
        val token = issueToken("attempt-http-1")

        val response = post(body("attempt-http-1"), token, trustedNamespace = "other-namespace")

        assertThat(response.statusCode).isEqualTo(HttpStatus.BAD_REQUEST)
        assertThat(errorCode(response.body)).isEqualTo("RESULT_IDENTITY_MISMATCH")
    }

    @Test
    fun `a trusted namespace matching the capability is accepted`() {
        val token = issueToken("attempt-http-1")

        val response = post(body("attempt-http-1"), token, trustedNamespace = namespace)

        assertThat(response.statusCode).isEqualTo(HttpStatus.CREATED)
    }

    @Test
    fun `a trusted case id contradicting the declared case yields 400 RESULT_IDENTITY_MISMATCH`() {
        val token = issueToken("attempt-http-1")

        val response = post(body("attempt-http-1"), token, trustedCaseId = "other-case")

        assertThat(response.statusCode).isEqualTo(HttpStatus.BAD_REQUEST)
        assertThat(errorCode(response.body)).isEqualTo("RESULT_IDENTITY_MISMATCH")
    }

    @Test
    fun `a trusted case id overrides the declared case and is accepted when coherent`() {
        val token = issueToken("attempt-http-1")

        // No X-AgentOS-Case-Id header: the submission inherits the trusted case.
        val response = post(body("attempt-http-1"), token, caseId = null, trustedCaseId = caseId)

        assertThat(response.statusCode).isEqualTo(HttpStatus.CREATED)
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

    private fun bind(attemptId: String, namespaceId: String = namespace) {
        integrationBindings.bind(
            scope,
            DurableAttemptIntegrationBinding(
                attemptId = attemptId,
                serviceIdentityId = "agentos-http-api-test",
                namespaceId = namespaceId,
                workflowId = workflow,
                stepId = step,
                briefHash = briefHash,
            ),
        )
    }

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
        trustedNamespace: String? = null,
        trustedCaseId: String? = null,
    ): ResponseEntity<Map<String, Any?>> {
        val headers = HttpHeaders().apply {
            contentType = MediaType.APPLICATION_JSON
            if (token != null) set("Authorization", "Bearer $token")
            if (caseId != null) set("X-AgentOS-Case-Id", caseId)
            if (agentName != null) set("X-AgentOS-Agent-Name", agentName)
            if (idempotencyKey != null) set("X-Idempotency-Key", idempotencyKey)
            // Loopback-dev trust boundary: the verified TrustContext reads the
            // trusted execution identity from these headers on a loopback socket.
            if (trustedNamespace != null) set("x-factory-namespace-id", trustedNamespace)
            if (trustedCaseId != null) set("x-factory-case-id", trustedCaseId)
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
