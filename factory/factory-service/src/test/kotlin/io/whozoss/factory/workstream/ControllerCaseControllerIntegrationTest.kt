package io.whozoss.factory.workstream

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

/**
 * HTTP boundary tests of the controller case lifecycle endpoints (Phase 9):
 * active case read (404 contract), first start (201), explicit compaction
 * (archive + renew, same agent identity), history, bounded context preview
 * and the `WORKSTREAM_BOUNDARY_VIOLATION` trust enforcement.
 */
class ControllerCaseControllerIntegrationTest : Neo4jIntegrationTest() {

    @Autowired
    private lateinit var restTemplate: TestRestTemplate

    @Autowired
    private lateinit var factoryProperties: FactoryProperties

    private val token: String
        get() = TestJwt.issueJwt(
            mapOf("principalId" to "controller-tester", "principalType" to "human"),
            factoryProperties.security.fakeIdpSecret,
        )

    private fun createWorkstream(extra: Map<String, Any?> = emptyMap()): ResponseEntity<Map<String, Any?>> =
        exchange(
            HttpMethod.POST,
            "/api/factory/workstreams",
            mapOf(
                "slug" to WORKSTREAM_ID,
                "name" to "Controller HTTP WS",
                "status" to "active",
                "namespaceId" to "ns-controller-http",
            ) + extra,
        )

    @Test
    fun `GET controller-case returns 404 when no case is active`() {
        createWorkstream(mapOf("controllerAgentRef" to "agent://controller"))

        val response = exchange(HttpMethod.GET, "/api/factory/workstreams/$WORKSTREAM_ID/controller-case")

        assertThat(response.statusCode.value()).isEqualTo(404)
        assertThat(errorCode(response)).isEqualTo("NOT_FOUND")
        assertThat(detailCode(response)).isEqualTo("NO_ACTIVE_CONTROLLER_CASE")
    }

    @Test
    fun `POST controller-case starts the first case and GET returns it`() {
        createWorkstream(mapOf("controllerAgentRef" to "agent://controller"))

        val started = exchange(HttpMethod.POST, "/api/factory/workstreams/$WORKSTREAM_ID/controller-case", emptyMap())

        assertThat(started.statusCode.value()).isEqualTo(201)
        val startedBody = started.body!!
        assertThat(startedBody["workstreamId"]).isEqualTo(WORKSTREAM_ID)
        assertThat(startedBody["status"]).isEqualTo("active")
        assertThat(startedBody["sequence"]).isEqualTo(1)
        assertThat(startedBody["controllerAgentRef"]).isEqualTo("agent://controller")
        assertThat(startedBody["caseId"] as String).isNotBlank()
        assertThat(startedBody["startedAt"] as String).isNotBlank()
        assertThat(startedBody["archivedAt"]).isNull()
        // The context summary blob is never echoed on the case view.
        assertThat(startedBody.containsKey("contextSummary")).isFalse()

        val fetched = exchange(HttpMethod.GET, "/api/factory/workstreams/$WORKSTREAM_ID/controller-case")
        assertThat(fetched.statusCode.value()).isEqualTo(200)
        assertThat(fetched.body!!["caseId"]).isEqualTo(startedBody["caseId"])
        assertThat(fetched.body!!["controllerAgentRef"]).isEqualTo("agent://controller")
    }

    @Test
    fun `POST controller-case compact archives and renews preserving the agent identity`() {
        createWorkstream(mapOf("controllerAgentRef" to "agent://controller"))
        val started = exchange(HttpMethod.POST, "/api/factory/workstreams/$WORKSTREAM_ID/controller-case", emptyMap())
        val firstCaseId = started.body!!["caseId"] as String

        val compacted = exchange(
            HttpMethod.POST,
            "/api/factory/workstreams/$WORKSTREAM_ID/controller-case/compact",
            mapOf("compactionReason" to "manual"),
        )

        assertThat(compacted.statusCode.value()).isEqualTo(200)
        val compactedBody = compacted.body!!
        assertThat(compactedBody["caseId"] as String).isNotEqualTo(firstCaseId)
        assertThat(compactedBody["sequence"]).isEqualTo(2)
        assertThat(compactedBody["status"]).isEqualTo("active")
        assertThat(compactedBody["controllerAgentRef"]).isEqualTo("agent://controller")
        assertThat(compactedBody["workstreamId"]).isEqualTo(WORKSTREAM_ID)

        val history = exchange(HttpMethod.GET, "/api/factory/workstreams/$WORKSTREAM_ID/controller-case/history")
        assertThat(history.statusCode.value()).isEqualTo(200)
        val historyBody = history.body!!
        assertThat(historyBody["workstreamId"]).isEqualTo(WORKSTREAM_ID)
        assertThat(historyBody["activeCaseId"]).isEqualTo(compactedBody["caseId"])
        @Suppress("UNCHECKED_CAST")
        val cases = historyBody["cases"] as List<Map<String, Any?>>
        assertThat(cases).hasSize(2)
        assertThat(cases[0]["caseId"]).isEqualTo(firstCaseId)
        assertThat(cases[0]["status"]).isEqualTo("archived")
        assertThat(cases[0]["archivedAt"] as String).isNotBlank()
        assertThat(cases[0]["compactionReason"]).isEqualTo("manual")
        assertThat(cases[1]["caseId"]).isEqualTo(compactedBody["caseId"])
        assertThat(cases[1]["status"]).isEqualTo("active")
    }

    @Test
    fun `GET controller-case context returns a bounded resumption package`() {
        createWorkstream(mapOf("controllerAgentRef" to "agent://controller"))

        val response = exchange(HttpMethod.GET, "/api/factory/workstreams/$WORKSTREAM_ID/controller-case/context")

        assertThat(response.statusCode.value()).isEqualTo(200)
        val body = response.body!!
        assertThat(body["workstreamId"]).isEqualTo(WORKSTREAM_ID)
        val sourceRevision = body["sourceRevision"] as String
        assertThat(sourceRevision).matches("^[0-9a-f]{16}$")

        @Suppress("UNCHECKED_CAST")
        val counts = body["counts"] as Map<String, Any?>
        assertThat(counts.keys).contains(
            "activeWorkflows",
            "running",
            "waitingHuman",
            "blocked",
            "attempts",
            "humanActions",
            "failedOracles",
            "environments",
        )
        @Suppress("UNCHECKED_CAST")
        assertThat((body["activeWorkflows"] as List<Any?>).size).isLessThanOrEqualTo(10)
        @Suppress("UNCHECKED_CAST")
        assertThat((body["openHumanInteractions"] as List<Any?>).size).isLessThanOrEqualTo(10)
        @Suppress("UNCHECKED_CAST")
        assertThat((body["blockers"] as List<Any?>).size).isLessThanOrEqualTo(10)
        @Suppress("UNCHECKED_CAST")
        assertThat((body["recentChanges"] as List<Any?>).size).isLessThanOrEqualTo(10)

        // Identical state -> identical package revision.
        val reread = exchange(HttpMethod.GET, "/api/factory/workstreams/$WORKSTREAM_ID/controller-case/context")
        assertThat(reread.body!!["sourceRevision"]).isEqualTo(sourceRevision)
    }

    @Test
    fun `starting a controller case without a controllerAgentRef is a 422`() {
        createWorkstream()

        val response = exchange(HttpMethod.POST, "/api/factory/workstreams/$WORKSTREAM_ID/controller-case", emptyMap())

        assertThat(response.statusCode.value()).isEqualTo(422)
        assertThat(errorCode(response)).isEqualTo("UNPROCESSABLE_ENTITY")
        assertThat(detailCode(response)).isEqualTo("CONTROLLER_AGENT_REF_REQUIRED")
    }

    @Test
    fun `a workstream outside the trusted scope is a 403 WORKSTREAM_BOUNDARY_VIOLATION`() {
        createWorkstream(mapOf("controllerAgentRef" to "agent://controller"))

        val read = exchange(HttpMethod.GET, "/api/factory/workstreams/ws-untrusted/controller-case")
        assertThat(read.statusCode.value()).isEqualTo(403)
        assertThat(errorCode(read)).isEqualTo("WORKSTREAM_BOUNDARY_VIOLATION")

        val history = exchange(HttpMethod.GET, "/api/factory/workstreams/ws-untrusted/controller-case/history")
        assertThat(history.statusCode.value()).isEqualTo(403)
        assertThat(errorCode(history)).isEqualTo("WORKSTREAM_BOUNDARY_VIOLATION")

        val context = exchange(HttpMethod.GET, "/api/factory/workstreams/ws-untrusted/controller-case/context")
        assertThat(context.statusCode.value()).isEqualTo(403)
        assertThat(errorCode(context)).isEqualTo("WORKSTREAM_BOUNDARY_VIOLATION")

        val start = exchange(HttpMethod.POST, "/api/factory/workstreams/ws-untrusted/controller-case", emptyMap())
        assertThat(start.statusCode.value()).isEqualTo(403)
        assertThat(errorCode(start)).isEqualTo("WORKSTREAM_BOUNDARY_VIOLATION")

        val compact = exchange(HttpMethod.POST, "/api/factory/workstreams/ws-untrusted/controller-case/compact", emptyMap())
        assertThat(compact.statusCode.value()).isEqualTo(403)
        assertThat(errorCode(compact)).isEqualTo("WORKSTREAM_BOUNDARY_VIOLATION")
    }

    private fun exchange(
        method: HttpMethod,
        path: String,
        body: Map<String, Any?>? = null,
        headers: Map<String, String> = emptyMap(),
    ): ResponseEntity<Map<String, Any?>> {
        val httpHeaders = HttpHeaders().apply {
            contentType = MediaType.APPLICATION_JSON
            setBearerAuth(token)
            headers.forEach { (name, value) -> set(name, value) }
        }
        return restTemplate.exchange(path, method, HttpEntity(body, httpHeaders), mapType())
    }

    private fun mapType(): ParameterizedTypeReference<Map<String, Any?>> =
        object : ParameterizedTypeReference<Map<String, Any?>>() {}

    private fun errorCode(response: ResponseEntity<Map<String, Any?>>): String? {
        @Suppress("UNCHECKED_CAST")
        val error = response.body!!["error"] as Map<String, Any?>
        return error["code"] as String?
    }

    private fun detailCode(response: ResponseEntity<Map<String, Any?>>): String? {
        @Suppress("UNCHECKED_CAST")
        val error = response.body!!["error"] as Map<String, Any?>
        @Suppress("UNCHECKED_CAST")
        val details = error["details"] as? Map<String, Any?>
        return details?.get("code") as String?
    }

    companion object {
        private const val WORKSTREAM_ID = "ws-default"
    }
}
