package io.whozoss.factory.planchange

import io.whozoss.factory.Neo4jIntegrationTest
import io.whozoss.factory.config.FactoryProperties
import io.whozoss.factory.web.TestJwt
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.boot.test.web.client.TestRestTemplate
import org.springframework.core.ParameterizedTypeReference
import org.springframework.http.HttpEntity
import org.springframework.http.HttpHeaders
import org.springframework.http.HttpMethod
import org.springframework.http.MediaType
import org.springframework.http.ResponseEntity
import java.util.UUID

/**
 * HTTP boundary tests of the governed replanning surface
 * `/api/factory/plan-change-proposals`.
 *
 * Exercises the real servlet filter chain (trust context) against the embedded
 * Neo4j engine, asserting the deterministic classification, the idempotent
 * submission contract, the immutable decision projection, the governance gates
 * (Rules 1–3) and the stable error envelope.
 */
class PlanChangeProposalControllerIntegrationTest : Neo4jIntegrationTest() {

    @Autowired
    private lateinit var restTemplate: TestRestTemplate

    @Autowired
    private lateinit var factoryProperties: FactoryProperties

    private val token: String
        get() = TestJwt.issueJwt(
            mapOf("principalId" to "plan-change-tester", "principalType" to "human"),
            factoryProperties.security.fakeIdpSecret,
        )

    private fun submitBody(
        workflowId: String,
        idempotencyKey: String,
        type: String = "DEPENDENCY",
        summary: String = "replace the broken edge",
    ): Map<String, Any?> =
        mapOf(
            "workflowId" to workflowId,
            "namespaceId" to NAMESPACE_ID,
            "expectedRevision" to 1,
            "reasonCode" to "ORACLE_FAILURE",
            "summary" to summary,
            "proposalType" to type,
            "affectedStepIds" to listOf("step-a", "step-b"),
            "proposedDependencyChanges" to if (type == "DEPENDENCY") {
                listOf(mapOf("op" to "ADD", "fromStepId" to "step-a", "toStepId" to "step-b"))
            } else {
                emptyList<Any>()
            },
            "evidenceRefs" to listOf("evidence-1"),
            "idempotencyKey" to idempotencyKey,
        )

    @Test
    fun `submit persists the deterministic classification and the initial decision event`() {
        val workflowId = newWorkflowId()
        val created = submit(submitBody(workflowId, newKey()))

        assertThat(created.statusCode.value()).isEqualTo(201)
        val data = data(created)
        assertThat(data["proposalId"] as String).startsWith("pcp-")
        assertThat(data["workflowId"]).isEqualTo(workflowId)
        assertThat(data["namespaceId"]).isEqualTo(NAMESPACE_ID)
        assertThat(data["workstreamId"]).isEqualTo(WORKSTREAM_ID)
        assertThat(data["kind"]).isEqualTo("DEPENDENCY_CHANGE_PROPOSAL")
        assertThat(data["recommendedVerdict"]).isEqualTo("GATE_REQUIRED")
        assertThat(data["status"]).isEqualTo("PENDING_VALIDATION")
        assertThat(data["revision"]).isEqualTo(1)
        assertThat(data["idempotent"]).isEqualTo(false)
        @Suppress("UNCHECKED_CAST")
        val decisions = data["decisions"] as List<Map<String, Any?>>
        assertThat(decisions).hasSize(1)
        assertThat(decisions[0]["status"]).isEqualTo("PENDING_VALIDATION")
        assertThat(decisions[0]["sequence"]).isEqualTo(1)
        // The canonical request hash is never exposed.
        assertThat(data).doesNotContainKey("requestHash")
    }

    @Test
    fun `an idempotent replay returns the persisted proposal, a divergent replay collides`() {
        val workflowId = newWorkflowId()
        val key = newKey()
        val created = submit(submitBody(workflowId, key))
        assertThat(created.statusCode.value()).isEqualTo(201)
        val proposalId = data(created)["proposalId"] as String

        val replay = submit(submitBody(workflowId, key))
        assertThat(replay.statusCode.value()).isEqualTo(200)
        assertThat(data(replay)["idempotent"]).isEqualTo(true)
        assertThat(data(replay)["proposalId"]).isEqualTo(proposalId)

        val divergent = submit(submitBody(workflowId, key, summary = "a different payload"))
        assertThat(divergent.statusCode.value()).isEqualTo(409)
        assertThat(errorCode(divergent)).isEqualTo("IDEMPOTENCY_KEY_COLLISION")
    }

    @Test
    fun `list returns the proposals of a workflow and filters by status`() {
        val workflowId = newWorkflowId()
        submit(submitBody(workflowId, newKey()))
        submit(submitBody(workflowId, newKey()))

        val listed = exchange(
            HttpMethod.GET,
            "/api/factory/plan-change-proposals?workflowId=$workflowId&namespaceId=$NAMESPACE_ID",
        )
        assertThat(listed.statusCode.value()).isEqualTo(200)
        @Suppress("UNCHECKED_CAST")
        val items = listed.body!!["data"] as List<Map<String, Any?>>
        assertThat(items).hasSize(2)
        assertThat(items.map { it["status"] }.distinct()).containsExactly("PENDING_VALIDATION")

        val filtered = exchange(
            HttpMethod.GET,
            "/api/factory/plan-change-proposals?workflowId=$workflowId&namespaceId=$NAMESPACE_ID&status=GATE_REQUIRED",
        )
        assertThat(filtered.statusCode.value()).isEqualTo(200)
        @Suppress("UNCHECKED_CAST")
        assertThat((filtered.body!!["data"] as List<Map<String, Any?>>)).isEmpty()

        val otherWorkflow = exchange(
            HttpMethod.GET,
            "/api/factory/plan-change-proposals?workflowId=${newWorkflowId()}&namespaceId=$NAMESPACE_ID",
        )
        @Suppress("UNCHECKED_CAST")
        assertThat((otherWorkflow.body!!["data"] as List<Map<String, Any?>>)).isEmpty()
    }

    @Test
    fun `get returns the proposal details with its decision timeline`() {
        val workflowId = newWorkflowId()
        val proposalId = data(submit(submitBody(workflowId, newKey())))["proposalId"] as String

        val fetched = exchange(
            HttpMethod.GET,
            "/api/factory/plan-change-proposals/$proposalId?workflowId=$workflowId&namespaceId=$NAMESPACE_ID",
        )
        assertThat(fetched.statusCode.value()).isEqualTo(200)
        val data = data(fetched)
        assertThat(data["proposalId"]).isEqualTo(proposalId)
        @Suppress("UNCHECKED_CAST")
        assertThat((data["decisions"] as List<Map<String, Any?>>)).hasSize(1)

        val missing = exchange(
            HttpMethod.GET,
            "/api/factory/plan-change-proposals/pcp-absent?workflowId=$workflowId&namespaceId=$NAMESPACE_ID",
        )
        assertThat(missing.statusCode.value()).isEqualTo(404)
        assertThat(errorCode(missing)).isEqualTo("PLAN_CHANGE_PROPOSAL_NOT_FOUND")
    }

    @Test
    fun `decide appends an immutable decision and refreshes the derived status`() {
        val workflowId = newWorkflowId()
        val proposalId = data(submit(submitBody(workflowId, newKey())))["proposalId"] as String

        val decided = decide(
            proposalId,
            workflowId,
            mapOf(
                "expectedRevision" to 1,
                "decision" to "GATE_REQUIRED",
                "reason" to "structural change, human review",
                "idempotencyKey" to newKey(),
            ),
        )
        assertThat(decided.statusCode.value()).isEqualTo(200)
        val data = data(decided)
        assertThat(data["status"]).isEqualTo("GATE_REQUIRED")
        assertThat(data["revision"]).isEqualTo(2)
        // The immutable payload is preserved across the decision.
        assertThat(data["kind"]).isEqualTo("DEPENDENCY_CHANGE_PROPOSAL")
        assertThat(data["summary"]).isEqualTo("replace the broken edge")
        @Suppress("UNCHECKED_CAST")
        val decisions = data["decisions"] as List<Map<String, Any?>>
        assertThat(decisions.map { it["status"] }).containsExactly("PENDING_VALIDATION", "GATE_REQUIRED")
        assertThat(decisions.map { it["sequence"] }).containsExactly(1, 2)
    }

    @Test
    fun `deciding AUTO_APPLIED on a NEW_STEP proposal is a PLAN_CHANGE_GATE_REQUIRED`() {
        val workflowId = newWorkflowId()
        val proposalId = data(submit(submitBody(workflowId, newKey(), type = "NEW_STEP")))["proposalId"] as String

        val decided = decide(
            proposalId,
            workflowId,
            mapOf("expectedRevision" to 1, "decision" to "AUTO_APPLIED"),
        )
        assertThat(decided.statusCode.value()).isEqualTo(409)
        assertThat(errorCode(decided)).isEqualTo("PLAN_CHANGE_GATE_REQUIRED")

        // The proposal is untouched by the rejected decision.
        val fetched = exchange(
            HttpMethod.GET,
            "/api/factory/plan-change-proposals/$proposalId?workflowId=$workflowId&namespaceId=$NAMESPACE_ID",
        )
        assertThat(data(fetched)["status"]).isEqualTo("PENDING_VALIDATION")
        assertThat(data(fetched)["revision"]).isEqualTo(1)
    }

    @Test
    fun `a Rule 1 retry proposal can be auto-applied within definition limits`() {
        val workflowId = newWorkflowId()
        val proposalId = data(submit(submitBody(workflowId, newKey(), type = "RETRY")))["proposalId"] as String

        val decided = decide(
            proposalId,
            workflowId,
            mapOf("expectedRevision" to 1, "decision" to "AUTO_APPLIED", "reason" to "simple retry"),
        )
        assertThat(decided.statusCode.value()).isEqualTo(200)
        assertThat(data(decided)["status"]).isEqualTo("AUTO_APPLIED")
    }

    @Test
    fun `a stale expected revision on decide is a REVISION_CONFLICT`() {
        val workflowId = newWorkflowId()
        val proposalId = data(submit(submitBody(workflowId, newKey())))["proposalId"] as String
        decide(
            proposalId,
            workflowId,
            mapOf("expectedRevision" to 1, "decision" to "GATE_REQUIRED", "idempotencyKey" to newKey()),
        )

        val stale = decide(
            proposalId,
            workflowId,
            mapOf("expectedRevision" to 1, "decision" to "REJECTED", "idempotencyKey" to newKey()),
        )
        assertThat(stale.statusCode.value()).isEqualTo(409)
        assertThat(errorCode(stale)).isEqualTo("REVISION_CONFLICT")
    }

    @Test
    fun `a missing workflow id on list is an INVALID_PLAN_CHANGE_QUERY`() {
        val response = exchange(HttpMethod.GET, "/api/factory/plan-change-proposals?namespaceId=$NAMESPACE_ID")
        assertThat(response.statusCode.value()).isEqualTo(400)
        assertThat(errorCode(response)).isEqualTo("INVALID_PLAN_CHANGE_QUERY")
    }

    @Test
    fun `a missing namespace is an INVALID_NAMESPACE_ID`() {
        val response = submit(submitBody(newWorkflowId(), newKey()) - "namespaceId")
        assertThat(response.statusCode.value()).isEqualTo(400)
        assertThat(errorCode(response)).isEqualTo("INVALID_NAMESPACE_ID")
    }

    @Test
    fun `an unknown field in the submit body is an INVALID_PLAN_CHANGE_PROPOSAL`() {
        val response = submit(submitBody(newWorkflowId(), newKey()) + ("unexpected" to "nope"))
        assertThat(response.statusCode.value()).isEqualTo(400)
        assertThat(errorCode(response)).isEqualTo("INVALID_PLAN_CHANGE_PROPOSAL")
    }

    @Test
    fun `an anonymous non-loopback caller is a TRUST_CONTEXT_UNAVAILABLE`() {
        val response = exchange(
            HttpMethod.GET,
            "/api/factory/plan-change-proposals?workflowId=wf-x&namespaceId=$NAMESPACE_ID",
            authenticated = false,
            forwardedFor = "203.0.113.9",
        )
        assertThat(response.statusCode.value()).isEqualTo(401)
        assertThat(errorCode(response)).isEqualTo("TRUST_CONTEXT_UNAVAILABLE")
    }

    private fun submit(body: Map<String, Any?>): ResponseEntity<Map<String, Any?>> =
        exchange(HttpMethod.POST, "/api/factory/plan-change-proposals", body)

    private fun decide(
        proposalId: String,
        workflowId: String,
        body: Map<String, Any?>,
    ): ResponseEntity<Map<String, Any?>> =
        exchange(
            HttpMethod.POST,
            "/api/factory/plan-change-proposals/$proposalId/decide?workflowId=$workflowId&namespaceId=$NAMESPACE_ID",
            body,
        )

    private fun exchange(
        method: HttpMethod,
        path: String,
        body: Map<String, Any?>? = null,
        authenticated: Boolean = true,
        forwardedFor: String? = null,
    ): ResponseEntity<Map<String, Any?>> {
        val httpHeaders = HttpHeaders().apply {
            contentType = MediaType.APPLICATION_JSON
            if (authenticated) setBearerAuth(token)
            forwardedFor?.let { set("X-Forwarded-For", it) }
        }
        return restTemplate.exchange(path, method, HttpEntity(body, httpHeaders), mapType())
    }

    private fun mapType(): ParameterizedTypeReference<Map<String, Any?>> =
        object : ParameterizedTypeReference<Map<String, Any?>>() {}

    private fun data(response: ResponseEntity<Map<String, Any?>>): Map<String, Any?> {
        @Suppress("UNCHECKED_CAST")
        return response.body!!["data"] as Map<String, Any?>
    }

    private fun errorCode(response: ResponseEntity<Map<String, Any?>>): String? {
        @Suppress("UNCHECKED_CAST")
        val error = response.body!!["error"] as Map<String, Any?>
        return error["code"] as String?
    }

    private fun newWorkflowId(): String = "wf-${UUID.randomUUID().toString().take(8)}"

    private fun newKey(): String = "key-${UUID.randomUUID().toString().take(8)}"

    companion object {
        private const val NAMESPACE_ID = "ns-planchange"
        private const val WORKSTREAM_ID = "ws-default"
    }
}
