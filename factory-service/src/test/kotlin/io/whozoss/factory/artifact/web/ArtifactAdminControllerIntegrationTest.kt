package io.whozoss.factory.artifact.web

import io.whozoss.factory.PostgresContainerSpec
import io.whozoss.factory.artifact.infrastructure.blob.ArtifactBlobClient
import io.whozoss.factory.artifact.port.ArtifactStore
import io.whozoss.factory.artifact.port.PutArtifactParams
import io.whozoss.factory.config.FactoryProperties
import io.whozoss.factory.persistence.TenantScope
import io.whozoss.factory.web.TestJwt
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.boot.test.context.SpringBootTest
import org.springframework.boot.test.web.client.TestRestTemplate
import org.springframework.core.ParameterizedTypeReference
import org.springframework.http.HttpEntity
import org.springframework.http.HttpHeaders
import org.springframework.http.HttpMethod
import org.springframework.http.MediaType
import org.springframework.http.ResponseEntity
import org.springframework.jdbc.core.JdbcTemplate
import org.testcontainers.junit.jupiter.Testcontainers

/**
 * Integration tests for the admin artifact governance HTTP boundary.
 *
 * They exercise the real servlet filter chain (correlation id, trust context)
 * against a PostgreSQL 16 instance, asserting the exact Node contracts: the
 * `FORBIDDEN_ADMIN_REQUIRED` 403 for non-admins, the `{ "data": ... }` success
 * envelope, the machine error codes of the purge / legal-hold refusals and the
 * `METHOD_NOT_ALLOWED` 405 for non-POST calls.
 */
@SpringBootTest(
    webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT,
    properties = ["factory.security.fake-idp-secret=artifact-admin-test-secret"],
)
@Testcontainers(disabledWithoutDocker = true)
class ArtifactAdminControllerIntegrationTest : PostgresContainerSpec() {

    @Autowired
    private lateinit var restTemplate: TestRestTemplate

    @Autowired
    private lateinit var jdbcTemplate: JdbcTemplate

    @Autowired
    private lateinit var artifactStore: ArtifactStore

    @Autowired
    private lateinit var blobClient: ArtifactBlobClient

    @Autowired
    private lateinit var factoryProperties: FactoryProperties

    private val scope = TenantScope(ORGANIZATION_ID, WORKSTREAM_ID)

    @BeforeEach
    fun setUp() {
        jdbcTemplate.update(
            "DELETE FROM workflow_instances WHERE organization_id = ? AND workstream_id = ?",
            ORGANIZATION_ID,
            WORKSTREAM_ID,
        )
        // Drain the shared in-memory blob store so each test starts from a clean slate.
        (blobClient.listObjectKeys("objects/") + blobClient.listObjectKeys("uploads/")).forEach { blobClient.deleteObject(it) }
        ensureWorkflowInstance("ns")
    }

    private val adminToken: String
        get() = TestJwt.issueJwt(
            mapOf("principalId" to "admin-user", "principalType" to "human", "scopes" to listOf("admin:*")),
            factoryProperties.security.fakeIdpSecret,
        )

    private val memberToken: String
        get() = TestJwt.issueJwt(
            mapOf("principalId" to "member-user", "principalType" to "human"),
            factoryProperties.security.fakeIdpSecret,
        )

    @Test
    fun `non-admin callers get 403 FORBIDDEN_ADMIN_REQUIRED on every admin route`() {
        val seed = seedArtifact(retentionDays = 0)

        val gc = post("/api/factory/admin/artifacts/gc", memberToken)
        assertThat(gc.statusCode.value()).isEqualTo(403)
        assertThat(errorCode(gc)).isEqualTo("FORBIDDEN_ADMIN_REQUIRED")

        val purge = post("/api/factory/admin/artifacts/${seed.id}/purge", memberToken, mapOf("reason" to "x"))
        assertThat(purge.statusCode.value()).isEqualTo(403)
        assertThat(errorCode(purge)).isEqualTo("FORBIDDEN_ADMIN_REQUIRED")

        val legalHold = post(
            "/api/factory/admin/artifacts/${seed.id}/legal-hold",
            memberToken,
            mapOf("legalHold" to true),
        )
        assertThat(legalHold.statusCode.value()).isEqualTo(403)
        assertThat(errorCode(legalHold)).isEqualTo("FORBIDDEN_ADMIN_REQUIRED")

        // The store was never touched.
        assertThat(artifactStore.getArtifactMetadata(scope, seed.id)!!.legalHold).isFalse()
    }

    @Test
    fun `GC reclaims staging uploads and audits blob anomalies`() {
        seedArtifact(retentionDays = 30, owner = "ns")
        blobClient.putObject("uploads/orphan.part", "leftover".toByteArray())
        blobClient.putObject("objects/orphan-blob", "orphan".toByteArray())

        val response = post("/api/factory/admin/artifacts/gc", adminToken)

        assertThat(response.statusCode.value()).isEqualTo(200)
        val data = data(response)
        @Suppress("UNCHECKED_CAST")
        val reclaimed = data["reclaimedStagingKeys"] as List<String>
        assertThat(reclaimed).contains("uploads/orphan.part")
        assertThat(blobClient.headObject("uploads/orphan.part")).isFalse()

        @Suppress("UNCHECKED_CAST")
        val anomalies = data["anomalies"] as List<Map<String, Any?>>
        assertThat(anomalies).anySatisfy {
            assertThat(it["type"]).isEqualTo("blob_without_pg_row")
            assertThat(it["storageKey"]).isEqualTo("objects/orphan-blob")
        }
    }

    @Test
    fun `purge succeeds on expired retention and is refused otherwise`() {
        val expired = seedArtifact(retentionDays = 0)
        val purged = post("/api/factory/admin/artifacts/${expired.id}/purge", adminToken, mapOf("reason" to "gdpr"))
        assertThat(purged.statusCode.value()).isEqualTo(200)
        val purgedData = data(purged)
        assertThat(purgedData["success"]).isEqualTo(true)
        assertThat(purgedData["status"]).isEqualTo("purged")
        @Suppress("UNCHECKED_CAST")
        val purgedMetadata = purgedData["metadata"] as Map<String, Any?>
        assertThat(purgedMetadata["availabilityStatus"]).isEqualTo("purged")

        val active = seedArtifact(retentionDays = 30)
        val refusedRetention = post("/api/factory/admin/artifacts/${active.id}/purge", adminToken, mapOf("reason" to "x"))
        assertThat(refusedRetention.statusCode.value()).isEqualTo(409)
        assertThat(errorCode(refusedRetention)).isEqualTo("RETENTION_ACTIVE")

        val held = seedArtifact(retentionDays = 0)
        artifactStore.setLegalHold(scope, held.id, true, "litigation")
        val refusedHold = post("/api/factory/admin/artifacts/${held.id}/purge", adminToken, mapOf("reason" to "x"))
        assertThat(refusedHold.statusCode.value()).isEqualTo(409)
        assertThat(errorCode(refusedHold)).isEqualTo("LEGAL_HOLD_ACTIVE")

        val missing = post("/api/factory/admin/artifacts/unknown-id/purge", adminToken, mapOf("reason" to "x"))
        assertThat(missing.statusCode.value()).isEqualTo(404)
        assertThat(errorCode(missing)).isEqualTo("NOT_FOUND")
    }

    @Test
    fun `legal hold can be placed, released and validated`() {
        val seed = seedArtifact(retentionDays = 0)

        val placed = post(
            "/api/factory/admin/artifacts/${seed.id}/legal-hold",
            adminToken,
            mapOf("legalHold" to true, "reason" to "litigation"),
        )
        assertThat(placed.statusCode.value()).isEqualTo(200)
        assertThat(data(placed)["legalHold"]).isEqualTo(true)
        assertThat(data(placed)["legalHoldReason"]).isEqualTo("litigation")

        val released = post(
            "/api/factory/admin/artifacts/${seed.id}/legal-hold",
            adminToken,
            mapOf("legalHold" to false),
        )
        assertThat(released.statusCode.value()).isEqualTo(200)
        assertThat(data(released)["legalHold"]).isEqualTo(false)

        val invalid = post("/api/factory/admin/artifacts/${seed.id}/legal-hold", adminToken, emptyMap())
        assertThat(invalid.statusCode.value()).isEqualTo(400)
        assertThat(errorCode(invalid)).isEqualTo("INVALID_LEGAL_HOLD")

        val invalidType = post(
            "/api/factory/admin/artifacts/${seed.id}/legal-hold",
            adminToken,
            mapOf("legalHold" to "yes"),
        )
        assertThat(invalidType.statusCode.value()).isEqualTo(400)
        assertThat(errorCode(invalidType)).isEqualTo("INVALID_LEGAL_HOLD")

        val missing = post(
            "/api/factory/admin/artifacts/unknown-id/legal-hold",
            adminToken,
            mapOf("legalHold" to true),
        )
        assertThat(missing.statusCode.value()).isEqualTo(404)
        assertThat(errorCode(missing)).isEqualTo("ARTIFACT_NOT_FOUND")
    }

    @Test
    fun `non-POST calls to an admin route return 405 METHOD_NOT_ALLOWED`() {
        val response = get("/api/factory/admin/artifacts/gc", adminToken)
        assertThat(response.statusCode.value()).isEqualTo(405)
        assertThat(errorCode(response)).isEqualTo("METHOD_NOT_ALLOWED")
    }

    private fun seedArtifact(retentionDays: Int, owner: String = "ns") =
        artifactStore.putArtifact(
            scope,
            PutArtifactParams(
                owner = owner,
                contentType = "text/plain",
                data = "payload-$retentionDays".toByteArray(),
                retentionDays = retentionDays,
            ),
        )

    private fun post(path: String, token: String, body: Map<String, Any?> = emptyMap()): ResponseEntity<Map<String, Any?>> {
        val headers = HttpHeaders().apply {
            contentType = MediaType.APPLICATION_JSON
            setBearerAuth(token)
        }
        return restTemplate.exchange(
            path,
            HttpMethod.POST,
            HttpEntity(body, headers),
            mapType(),
        )
    }

    private fun get(path: String, token: String): ResponseEntity<Map<String, Any?>> {
        val headers = HttpHeaders().apply { setBearerAuth(token) }
        return restTemplate.exchange(path, HttpMethod.GET, HttpEntity<Void>(headers), mapType())
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

    private fun ensureWorkflowInstance(namespaceId: String) {
        jdbcTemplate.update(
            """
            INSERT INTO workflow_instances (
              organization_id, workstream_id, namespace_id, workflow_id, revision, status, instance_json, projection_json
            ) VALUES (?, ?, ?, 'default', 1, 'active', '{}'::jsonb, '{}'::jsonb)
            ON CONFLICT DO NOTHING
            """.trimIndent(),
            ORGANIZATION_ID,
            WORKSTREAM_ID,
            namespaceId,
        )
    }

    companion object {
        private const val ORGANIZATION_ID = "org-local-dev"
        private const val WORKSTREAM_ID = "ws-default"
    }
}
