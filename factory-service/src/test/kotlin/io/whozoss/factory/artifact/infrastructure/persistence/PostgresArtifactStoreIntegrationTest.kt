package io.whozoss.factory.artifact.infrastructure.persistence

import io.whozoss.factory.PostgresContainerSpec
import io.whozoss.factory.artifact.config.ArtifactProperties
import io.whozoss.factory.artifact.domain.ArtifactAvailabilityStatus
import io.whozoss.factory.artifact.domain.ArtifactHash
import io.whozoss.factory.artifact.domain.ArtifactRetentionStatus
import io.whozoss.factory.artifact.error.ArtifactAdminException
import io.whozoss.factory.artifact.infrastructure.blob.InMemoryArtifactBlobClient
import io.whozoss.factory.artifact.port.PutArtifactParams
import io.whozoss.factory.artifact.service.ArtifactAdminService
import io.whozoss.factory.artifact.service.ArtifactAdminPurgeStatus
import io.whozoss.factory.artifact.service.ArtifactGcAnomalyType
import io.whozoss.factory.persistence.TenantScope
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.boot.test.context.SpringBootTest
import org.springframework.jdbc.core.JdbcTemplate
import org.testcontainers.junit.jupiter.Testcontainers
import java.time.Instant

/**
 * Integration tests for [PostgresArtifactStore] and [ArtifactAdminService]
 * against a real PostgreSQL 16 instance (Testcontainers). They cover the
 * metadata round-trip with the three orthogonal statuses, the upload-then-commit
 * protocol, the purge lifecycle, legal holds and the triggered GC audit.
 */
@SpringBootTest
@Testcontainers(disabledWithoutDocker = true)
class PostgresArtifactStoreIntegrationTest : PostgresContainerSpec() {

    @Autowired
    private lateinit var jdbcTemplate: JdbcTemplate

    @Autowired
    private lateinit var properties: ArtifactProperties

    private val scope = TenantScope(ORGANIZATION_ID, WORKSTREAM_ID)
    private val fixedNow: Instant = Instant.parse("2026-01-01T00:00:00Z")

    private lateinit var blobClient: InMemoryArtifactBlobClient
    private lateinit var store: PostgresArtifactStore
    private lateinit var adminService: ArtifactAdminService

    @BeforeEach
    fun setUp() {
        jdbcTemplate.update("DELETE FROM workflow_instances WHERE organization_id = ?", ORGANIZATION_ID)
        blobClient = InMemoryArtifactBlobClient()
        store = PostgresArtifactStore(
            jdbcTemplate = jdbcTemplate,
            blobClient = blobClient,
            defaultRetentionDays = properties.retentionDays,
            now = { fixedNow },
        )
        adminService = ArtifactAdminService(store, store, blobClient, now = { fixedNow })
        ensureWorkflowInstance("ns", "default")
        ensureWorkflowInstance("team", "flow")
    }

    @Test
    fun `put, get and open round-trip preserves the three orthogonal statuses`() {
        val payload = "artifact-payload".toByteArray()
        val metadata = store.putArtifact(
            scope,
            PutArtifactParams(owner = "ns", contentType = "text/plain", data = payload, retentionDays = 30),
        )

        assertThat(metadata.availabilityStatus).isEqualTo(ArtifactAvailabilityStatus.AVAILABLE)
        assertThat(metadata.retentionStatus).isEqualTo(ArtifactRetentionStatus.ACTIVE)
        assertThat(metadata.legalHold).isFalse()
        assertThat(metadata.retentionDays).isEqualTo(30)
        assertThat(metadata.hash).isEqualTo(ArtifactHash.compute(payload))

        // Upload-then-commit: the content-addressed object exists, staging is gone.
        assertThat(blobClient.headObject("objects/${ArtifactHash.digest(metadata.hash)}")).isTrue()
        assertThat(blobClient.headObject("uploads/${metadata.id}.part")).isFalse()

        val found = store.getArtifactMetadata(scope, metadata.id)
        assertThat(found).isNotNull
        assertThat(found!!.hash).isEqualTo(metadata.hash)
        assertThat(found.size).isEqualTo(payload.size.toLong())
        assertThat(found.owner).isEqualTo("ns")
        assertThat(found.retentionStatus).isEqualTo(ArtifactRetentionStatus.ACTIVE)

        val opened = store.openArtifact(scope, metadata.id)
        assertThat(opened).isNotNull
        assertThat(opened!!.stream.readBytes()).isEqualTo(payload)
        assertThat(store.openArtifact(scope, "unknown")).isNull()

        // Orthogonality: a legal hold does not change availability nor retention.
        val held = store.setLegalHold(scope, metadata.id, true, "litigation")
        assertThat(held!!.legalHold).isTrue()
        assertThat(held.availabilityStatus).isEqualTo(ArtifactAvailabilityStatus.AVAILABLE)
        assertThat(held.retentionStatus).isEqualTo(ArtifactRetentionStatus.ACTIVE)
    }

    @Test
    fun `structured owner namespaces the persisted row`() {
        val metadata = store.putArtifact(
            scope,
            PutArtifactParams(owner = "team/flow", contentType = "application/octet-stream", data = "x".toByteArray(), retentionDays = 1),
        )
        val row = jdbcTemplate.queryForMap(
            "SELECT organization_id, workstream_id, namespace_id, workflow_id FROM artifacts WHERE artifact_id = ?",
            metadata.id,
        )
        assertThat(row["organization_id"]).isEqualTo(ORGANIZATION_ID)
        assertThat(row["workstream_id"]).isEqualTo(WORKSTREAM_ID)
        assertThat(row["namespace_id"]).isEqualTo("team")
        assertThat(row["workflow_id"]).isEqualTo("flow")
        assertThat(store.getArtifactMetadata(scope, metadata.id)!!.owner).isEqualTo("team/flow")
    }

    @Test
    fun `expired retention allows purge and records the purge`() {
        val metadata = store.putArtifact(
            scope,
            PutArtifactParams(owner = "ns", contentType = "text/plain", data = "expiring".toByteArray(), retentionDays = 0),
        )
        assertThat(metadata.retentionStatus).isEqualTo(ArtifactRetentionStatus.EXPIRED)

        assertThat(store.purgeArtifact(scope, metadata.id, "retention-elapsed")).isTrue()

        val purged = store.getArtifactMetadata(scope, metadata.id)!!
        assertThat(purged.availabilityStatus).isEqualTo(ArtifactAvailabilityStatus.PURGED)
        assertThat(purged.purgeReason).isEqualTo("retention-elapsed")
        assertThat(purged.purgedAt).isNotNull
        assertThat(store.openArtifact(scope, metadata.id)).isNull()
        assertThat(store.purgeArtifact(scope, metadata.id, "again")).isFalse()

        val row = jdbcTemplate.queryForMap(
            "SELECT availability_status, purge_reason, legal_hold FROM artifacts WHERE artifact_id = ?",
            metadata.id,
        )
        assertThat(row["availability_status"]).isEqualTo("purged")
        assertThat(row["purge_reason"]).isEqualTo("retention-elapsed")
        assertThat(row["legal_hold"]).isEqualTo(false)
    }

    @Test
    fun `active retention refuses purge and delete`() {
        val metadata = store.putArtifact(
            scope,
            PutArtifactParams(owner = "ns", contentType = "text/plain", data = "retained".toByteArray(), retentionDays = 30),
        )
        assertThat(store.purgeArtifact(scope, metadata.id)).isFalse()
        assertThat(store.deleteArtifact(scope, metadata.id)).isFalse()
        assertThat(store.getArtifactMetadata(scope, metadata.id)!!.availabilityStatus)
            .isEqualTo(ArtifactAvailabilityStatus.AVAILABLE)
    }

    @Test
    fun `legal hold blocks destruction until released`() {
        val metadata = store.putArtifact(
            scope,
            PutArtifactParams(owner = "ns", contentType = "text/plain", data = "held".toByteArray(), retentionDays = 0),
        )

        val held = store.setLegalHold(scope, metadata.id, true, "litigation")!!
        assertThat(held.legalHold).isTrue()
        assertThat(held.legalHoldReason).isEqualTo("litigation")
        assertThat(held.legalHoldSetAt).isNotNull

        assertThat(store.deleteArtifact(scope, metadata.id)).isFalse()
        assertThat(store.purgeArtifact(scope, metadata.id)).isFalse()
        assertThat(store.openArtifact(scope, metadata.id)).isNotNull()

        val released = store.setLegalHold(scope, metadata.id, false)!!
        assertThat(released.legalHold).isFalse()
        assertThat(released.legalHoldReason).isNull()
        assertThat(released.legalHoldSetAt).isNull()

        assertThat(store.purgeArtifact(scope, metadata.id, "erasure request")).isTrue()
        assertThat(store.setLegalHold(scope, "unknown-id", true)).isNull()
    }

    @Test
    fun `triggered GC reclaims staging uploads and audits anomalies`() {
        // A consistent artifact: blob present, row available.
        store.putArtifact(
            scope,
            PutArtifactParams(owner = "ns", contentType = "text/plain", data = "consistent".toByteArray(), retentionDays = 30),
        )
        // A purged artifact: row marked purged.
        val toPurge = store.putArtifact(
            scope,
            PutArtifactParams(owner = "ns", contentType = "text/plain", data = "purge-me".toByteArray(), retentionDays = 0),
        )
        store.purgeArtifact(scope, toPurge.id, "retention-elapsed")
        // An available artifact whose blob is missing.
        val missing = store.putArtifact(
            scope,
            PutArtifactParams(owner = "ns", contentType = "text/plain", data = "missing-blob".toByteArray(), retentionDays = 30),
        )
        blobClient.deleteObject("objects/${ArtifactHash.digest(missing.hash)}")
        // Orphans.
        blobClient.putObject("uploads/orphan.part", "leftover".toByteArray())
        blobClient.putObject("objects/orphan-blob", "orphan".toByteArray())

        val report = adminService.collectAndAuditGarbage(scope)

        assertThat(report.reclaimedStagingKeys).containsExactly("uploads/orphan.part")
        assertThat(blobClient.headObject("uploads/orphan.part")).isFalse()
        assertThat(report.scannedMetadataRows).isEqualTo(3)
        assertThat(report.scannedBlobKeys).contains("objects/orphan-blob")

        val byType = report.anomalies.groupBy { it.type }
        assertThat(byType[ArtifactGcAnomalyType.BLOB_WITHOUT_PG_ROW].orEmpty().map { it.storageKey })
            .containsExactly("objects/orphan-blob")
        val purgedOrMissing = byType[ArtifactGcAnomalyType.PG_PURGED_OR_MISSING_BLOB].orEmpty().map { it.artifactId }
        assertThat(purgedOrMissing).contains(toPurge.id, missing.id)
    }

    @Test
    fun `admin purge use case classifies refusals with the Node machine codes`() {
        val active = store.putArtifact(
            scope,
            PutArtifactParams(owner = "ns", contentType = "text/plain", data = "active".toByteArray(), retentionDays = 30),
        )
        assertThat(adminService.purgeArtifactAdmin(scope, "does-not-exist", "x").status)
            .isEqualTo(ArtifactAdminPurgeStatus.NOT_FOUND)
        assertThat(adminService.purgeArtifactAdmin(scope, active.id, "x").status)
            .isEqualTo(ArtifactAdminPurgeStatus.RETENTION_ACTIVE)

        val held = store.putArtifact(
            scope,
            PutArtifactParams(owner = "ns", contentType = "text/plain", data = "held".toByteArray(), retentionDays = 0),
        )
        store.setLegalHold(scope, held.id, true, "litigation")
        assertThat(adminService.purgeArtifactAdmin(scope, held.id, "x").status)
            .isEqualTo(ArtifactAdminPurgeStatus.LEGAL_HOLD_ACTIVE)

        val expired = store.putArtifact(
            scope,
            PutArtifactParams(owner = "ns", contentType = "text/plain", data = "expired".toByteArray(), retentionDays = 0),
        )
        val result = adminService.purgeArtifactAdmin(scope, expired.id, "gdpr")
        assertThat(result.success).isTrue()
        assertThat(result.status).isEqualTo(ArtifactAdminPurgeStatus.PURGED)
        assertThat(result.metadata!!.availabilityStatus).isEqualTo(ArtifactAvailabilityStatus.PURGED)

        val thrown = runCatching { adminService.setLegalHoldAdmin(scope, "missing", true, null) }.exceptionOrNull()
        assertThat(thrown).isInstanceOf(ArtifactAdminException::class.java)
        assertThat((thrown as ArtifactAdminException).errorCode).isEqualTo("ARTIFACT_NOT_FOUND")
        assertThat(thrown.statusCode).isEqualTo(404)
    }

    private fun ensureWorkflowInstance(namespaceId: String, workflowId: String) {
        jdbcTemplate.update(
            """
            INSERT INTO workflow_instances (
              organization_id, workstream_id, namespace_id, workflow_id, revision, status, instance_json, projection_json
            ) VALUES (?, ?, ?, ?, 1, 'active', '{}'::jsonb, '{}'::jsonb)
            ON CONFLICT DO NOTHING
            """.trimIndent(),
            ORGANIZATION_ID,
            WORKSTREAM_ID,
            namespaceId,
            workflowId,
        )
    }

    companion object {
        private const val ORGANIZATION_ID = "org-artifact-it"
        private const val WORKSTREAM_ID = "ws-artifact-it"
    }
}
