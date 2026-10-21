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
import io.whozoss.factory.config.FactoryProperties
import io.whozoss.factory.web.FakeIdp
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
 * The worker result route with authentication ENABLED (`insecure-mode=false`).
 *
 * The per-class property override gives this class its own Spring context (and its own
 * in-process Neo4j harness), exactly like `WorkflowActionsHttpTest`: the shared context of
 * the other integration tests keeps running in insecure mode.
 *
 * Only the authentication filter decides here: a missing or garbage bearer is a 401 before
 * the controller runs, a valid Factory JWT reaches the route and submits.
 */
@TestPropertySource(properties = ["factory.security.insecure-mode=false"])
class AgentStepResultControllerSecureModeHttpTest : Neo4jDomainIntegrationTest() {

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

    private val namespace = "ns-http-secure"
    private val workflow = "wf-http-secure"
    private val step = "step-http-secure"
    private val caseId = "case-http-secure"
    private val agentName = "Agent"
    private val frozenBrief = "frozen-secure-brief"
    private val attemptId = "attempt-http-secure"

    @BeforeEach
    fun seedAttempt() {
        attempts.insert(
            scope,
            AgentStepAttemptRecord(namespace, workflow, step, attemptId, "agent-1", "running", 1, "{}"),
        )
        val token = service.issue(
            scope,
            AgentStepResultCapabilityIdentity(
                attemptId = attemptId,
                workflowId = workflow,
                stepId = step,
                namespaceId = namespace,
                caseId = caseId,
                agentName = agentName,
                briefHash = CanonicalJsonHash.sha256(frozenBrief),
            ),
        ).token
        durableAttempts.register(
            scope,
            DurableAgentAttempt(
                attemptId = attemptId,
                caseId = caseId,
                namespaceId = namespace,
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

    @Test
    fun `no bearer yields 401 UNAUTHENTICATED`() {
        val response = post(bearer = null)

        assertThat(response.statusCode).isEqualTo(HttpStatus.UNAUTHORIZED)
        assertThat(errorCode(response.body)).isEqualTo("UNAUTHENTICATED")
        assertThat(service.acceptedResult(scope, namespace, workflow, step, attemptId)).isNull()
    }

    @Test
    fun `a garbage bearer yields 401 UNAUTHENTICATED`() {
        val response = post(bearer = "not-a-jwt-just-a-stale-static-token")

        assertThat(response.statusCode).isEqualTo(HttpStatus.UNAUTHORIZED)
        assertThat(errorCode(response.body)).isEqualTo("UNAUTHENTICATED")
        assertThat(service.acceptedResult(scope, namespace, workflow, step, attemptId)).isNull()
    }

    @Test
    fun `a valid Factory JWT submits the result`() {
        val jwt = FakeIdp.issueJwt(
            mapOf("sub" to "agentos-worker", "exp" to System.currentTimeMillis() / 1000 + 300),
            properties.security.fakeIdpSecret,
        )

        val response = post(bearer = jwt)

        assertThat(response.statusCode).isEqualTo(HttpStatus.CREATED)
        assertThat(data(response.body)!!["idempotent"]).isEqualTo(false)
        assertThat(service.acceptedResult(scope, namespace, workflow, step, attemptId)).isNotNull
    }

    private fun post(bearer: String?): ResponseEntity<Map<String, Any?>> {
        val headers = HttpHeaders().apply {
            contentType = MediaType.APPLICATION_JSON
            set("Idempotency-Key", "secure-key-1")
            if (bearer != null) setBearerAuth(bearer)
        }
        val body = """{"attemptId":"$attemptId","result":{"status":"PASS","summary":"ok","claims":{"modifiedFiles":[]}}}"""
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
}
