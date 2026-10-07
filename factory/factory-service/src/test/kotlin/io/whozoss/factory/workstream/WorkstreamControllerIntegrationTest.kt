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
 * HTTP boundary tests of the versioned workstream registry and its read-only
 * aggregated projection endpoint.
 *
 * Exercises the real servlet filter chain (trust context) against the embedded
 * Neo4j engine, asserting the enriched CRUD contract, the optimistic-locking
 * `REVISION_CONFLICT`, the ETag/`workstreamRevision` coherence, the strict
 * bounds and the `WORKSTREAM_BOUNDARY_VIOLATION` trust enforcement.
 */
class WorkstreamControllerIntegrationTest : Neo4jIntegrationTest() {

    @Autowired
    private lateinit var restTemplate: TestRestTemplate

    @Autowired
    private lateinit var factoryProperties: FactoryProperties

    private val token: String
        get() = TestJwt.issueJwt(
            mapOf("principalId" to "workstream-tester", "principalType" to "human"),
            factoryProperties.security.fakeIdpSecret,
        )

    private fun createWorkstream(slug: String = WORKSTREAM_ID, extra: Map<String, Any?> = emptyMap()): ResponseEntity<Map<String, Any?>> =
        exchange(
            HttpMethod.POST,
            "/api/factory/workstreams",
            mapOf(
                "slug" to slug,
                "name" to "HTTP Workstream",
                "status" to "active",
                "namespaceId" to "ns-http",
            ) + extra,
        )

    @Test
    fun `create persists the enriched registry fields and reads them back`() {
        val created = createWorkstream(
            extra = mapOf(
                "controllerAgentRef" to "agent://controller",
                "allowedWorkflowTypes" to listOf("wf-http"),
                "governancePolicyRef" to "policy://gov",
            ),
        )
        assertThat(created.statusCode.value()).isEqualTo(201)
        val body = created.body!!
        assertThat(body["slug"]).isEqualTo(WORKSTREAM_ID)
        assertThat(body["workstreamId"]).isEqualTo(WORKSTREAM_ID)
        assertThat(body["title"]).isEqualTo("HTTP Workstream")
        assertThat(body["status"]).isEqualTo("active")
        assertThat(body["controllerAgentRef"]).isEqualTo("agent://controller")
        assertThat(body["allowedWorkflowTypes"]).isEqualTo(listOf("wf-http"))
        assertThat(body["governancePolicyRef"]).isEqualTo("policy://gov")
        assertThat(body["revision"]).isEqualTo(1)
        assertThat(body["createdAt"]).isNotNull()
        assertThat(body["updatedAt"]).isNotNull()

        val fetched = exchange(HttpMethod.GET, "/api/factory/workstreams/$WORKSTREAM_ID")
        assertThat(fetched.statusCode.value()).isEqualTo(200)
        assertThat(fetched.body!!["slug"]).isEqualTo(WORKSTREAM_ID)
        assertThat(fetched.body!!["namespaceId"]).isEqualTo("ns-http")
    }

    @Test
    fun `update bumps the revision and a stale expected revision is a REVISION_CONFLICT`() {
        createWorkstream()

        val updated = exchange(
            HttpMethod.PUT,
            "/api/factory/workstreams/$WORKSTREAM_ID",
            mapOf("status" to "paused", "expectedRevision" to 1),
        )
        assertThat(updated.statusCode.value()).isEqualTo(200)
        assertThat(updated.body!!["revision"]).isEqualTo(2)
        assertThat(updated.body!!["status"]).isEqualTo("paused")

        val stale = exchange(
            HttpMethod.PUT,
            "/api/factory/workstreams/$WORKSTREAM_ID",
            mapOf("status" to "archived", "expectedRevision" to 1),
        )
        assertThat(stale.statusCode.value()).isEqualTo(409)
        assertThat(errorCode(stale)).isEqualTo("REVISION_CONFLICT")

        // The If-Match header is an equivalent optimistic-lock precondition.
        val viaHeader = exchange(
            HttpMethod.PUT,
            "/api/factory/workstreams/$WORKSTREAM_ID",
            mapOf("status" to "archived"),
            headers = mapOf("If-Match" to "\"2\""),
        )
        assertThat(viaHeader.statusCode.value()).isEqualTo(200)
        assertThat(viaHeader.body!!["revision"]).isEqualTo(3)
        assertThat(viaHeader.body!!["status"]).isEqualTo("archived")
    }

    @Test
    fun `the projection endpoint returns the bounded DTO with a coherent ETag`() {
        createWorkstream()

        val response = exchange(HttpMethod.GET, "/api/factory/workstreams/$WORKSTREAM_ID/projection")

        assertThat(response.statusCode.value()).isEqualTo(200)
        val body = response.body!!
        assertThat(body["workstreamId"]).isEqualTo(WORKSTREAM_ID)
        assertThat(body["status"]).isEqualTo("active")
        val revision = body["workstreamRevision"] as String
        assertThat(revision).matches("^[0-9a-f]{16}$")

        // The ETag header carries the exact same revision, quoted.
        assertThat(response.headers.eTag).isEqualTo("\"$revision\"")

        // The bounded section shape is present and coherent.
        @Suppress("UNCHECKED_CAST")
        val activeWorkflows = body["activeWorkflows"] as Map<String, Any?>
        assertThat(activeWorkflows["count"]).isEqualTo(0)
        assertThat(activeWorkflows["truncated"]).isEqualTo(false)
        @Suppress("UNCHECKED_CAST")
        assertThat((body["steps"] as Map<String, Any?>)["running"]).isEqualTo(0)
        assertThat(body["boundaryViolations"]).isEqualTo(0)

        // Two identical reads produce the identical ETag.
        val reread = exchange(HttpMethod.GET, "/api/factory/workstreams/$WORKSTREAM_ID/projection")
        assertThat(reread.body!!["workstreamRevision"]).isEqualTo(revision)
    }

    @Test
    fun `the projection of an unknown workstream is a NOT_FOUND`() {
        val response = exchange(HttpMethod.GET, "/api/factory/workstreams/$WORKSTREAM_ID/projection")
        assertThat(response.statusCode.value()).isEqualTo(404)
        assertThat(errorCode(response)).isEqualTo("NOT_FOUND")
    }

    @Test
    fun `a path workstream outside the trusted scope is a WORKSTREAM_BOUNDARY_VIOLATION`() {
        createWorkstream()

        val projection = exchange(HttpMethod.GET, "/api/factory/workstreams/ws-untrusted/projection")
        assertThat(projection.statusCode.value()).isEqualTo(403)
        assertThat(errorCode(projection)).isEqualTo("WORKSTREAM_BOUNDARY_VIOLATION")

        val read = exchange(HttpMethod.GET, "/api/factory/workstreams/ws-untrusted")
        assertThat(read.statusCode.value()).isEqualTo(403)
        assertThat(errorCode(read)).isEqualTo("WORKSTREAM_BOUNDARY_VIOLATION")

        val write = exchange(HttpMethod.PUT, "/api/factory/workstreams/ws-untrusted", mapOf("status" to "paused"))
        assertThat(write.statusCode.value()).isEqualTo(403)
        assertThat(errorCode(write)).isEqualTo("WORKSTREAM_BOUNDARY_VIOLATION")
    }

    @Test
    fun `an out-of-range limit is coerced into the strict bounds`() {
        createWorkstream()

        val response = exchange(HttpMethod.GET, "/api/factory/workstreams/$WORKSTREAM_ID/projection?limit=500")
        assertThat(response.statusCode.value()).isEqualTo(200)
        @Suppress("UNCHECKED_CAST")
        val recentChanges = response.body!!["recentChanges"] as Map<String, Any?>
        @Suppress("UNCHECKED_CAST")
        assertThat((recentChanges["items"] as List<Any?>).size).isLessThanOrEqualTo(50)
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

    companion object {
        private const val WORKSTREAM_ID = "ws-default"
    }
}
