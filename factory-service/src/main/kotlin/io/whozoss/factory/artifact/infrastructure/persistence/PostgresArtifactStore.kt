package io.whozoss.factory.artifact.infrastructure.persistence

import com.fasterxml.jackson.databind.ObjectMapper
import io.whozoss.factory.artifact.domain.ArtifactAvailabilityStatus
import io.whozoss.factory.artifact.domain.ArtifactGovernance
import io.whozoss.factory.artifact.domain.ArtifactHash
import io.whozoss.factory.artifact.domain.ArtifactMetadata
import io.whozoss.factory.artifact.domain.ArtifactRetentionStatus
import io.whozoss.factory.artifact.infrastructure.blob.ArtifactBlobClient
import io.whozoss.factory.artifact.port.ArtifactGcMetadataLister
import io.whozoss.factory.artifact.port.ArtifactGcMetadataRow
import io.whozoss.factory.artifact.port.ArtifactStore
import io.whozoss.factory.artifact.port.OpenArtifactResult
import io.whozoss.factory.artifact.port.PutArtifactParams
import io.whozoss.factory.persistence.TenantScope
import org.springframework.jdbc.core.JdbcTemplate
import org.springframework.jdbc.core.RowMapper
import java.sql.ResultSet
import java.sql.Timestamp
import java.time.Instant

/**
 * Composed PostgreSQL + object-storage implementation of [ArtifactStore].
 *
 * Ported from `factory/src/adapters/artifact/postgres-artifact-store.ts`. The
 * immutable binary payload lives in object storage, content-addressed under
 * `objects/<sha256-hex>`; the authoritative metadata (statuses, retention
 * window, legal hold and purge record) lives in the PostgreSQL `artifacts` row.
 *
 * `putArtifact` follows the *upload-then-commit* protocol: the payload is
 * uploaded to a staging key `uploads/<id>.part`, promoted to its
 * content-addressed key, verified in object storage, and only then is the
 * PostgreSQL row committed. A failed verification or commit therefore leaves no
 * authoritative row — only a reclaimable orphan in object storage.
 */
class PostgresArtifactStore(
    private val jdbcTemplate: JdbcTemplate,
    private val blobClient: ArtifactBlobClient,
    private val defaultRetentionDays: Int = ArtifactGovernance.DEFAULT_RETENTION_DAYS,
    private val uploadPrefix: String = DEFAULT_UPLOAD_PREFIX,
    private val objectPrefix: String = DEFAULT_OBJECT_PREFIX,
    private val objectMapper: ObjectMapper = ObjectMapper(),
    private val now: () -> Instant = Instant::now,
) : ArtifactStore, ArtifactGcMetadataLister {

    override fun putArtifact(scope: TenantScope, params: PutArtifactParams): ArtifactMetadata {
        val nowInstant = now()
        val id = ArtifactHash.createArtifactId()
        val hash = ArtifactHash.compute(params.data)
        val retentionDays = params.retentionDays ?: defaultRetentionDays
        val metadata = ArtifactGovernance.buildArtifactMetadata(
            id = id,
            owner = params.owner,
            contentType = params.contentType,
            data = params.data,
            retentionDays = retentionDays,
            now = nowInstant,
        )
        val stagingKey = "$uploadPrefix/$id.part"
        val contentKey = contentKey(hash)

        // 1. Upload the payload to the staging key.
        blobClient.putObject(stagingKey, params.data, "application/octet-stream")
        // 2. Promote the staging object to its immutable content-addressed key.
        blobClient.copyObject(stagingKey, contentKey)
        // 3. Verify the object exists in object storage before the commit.
        if (!blobClient.headObject(contentKey)) {
            throw IllegalStateException("ARTIFACT_OBJECT_VERIFICATION_FAILED")
        }
        // 4. Commit the authoritative metadata row in PostgreSQL.
        saveMetadata(scope, metadata, contentKey)
        // 5. Best-effort removal of the staging object.
        bestEffortDelete(stagingKey)
        return metadata
    }

    override fun getArtifactMetadata(scope: TenantScope, artifactId: String): ArtifactMetadata? {
        val row = selectRow(scope, artifactId) ?: return null
        return ArtifactGovernance.refreshArtifactMetadata(toMetadata(row), now())
    }

    override fun openArtifact(scope: TenantScope, artifactId: String): OpenArtifactResult? {
        val row = selectRow(scope, artifactId) ?: return null
        val metadata = ArtifactGovernance.refreshArtifactMetadata(toMetadata(row), now())
        if (metadata.availabilityStatus == ArtifactAvailabilityStatus.PURGED) return null
        val stream = blobClient.getObject(row.storageKey) ?: return null
        return OpenArtifactResult(stream = stream, metadata = metadata)
    }

    override fun deleteArtifact(scope: TenantScope, artifactId: String, reason: String?): Boolean =
        destroy(scope, artifactId, reason ?: "deleted")

    override fun purgeArtifact(scope: TenantScope, artifactId: String, reason: String?): Boolean =
        destroy(scope, artifactId, reason ?: "retention-expired")

    override fun setLegalHold(
        scope: TenantScope,
        artifactId: String,
        legalHold: Boolean,
        reason: String?,
    ): ArtifactMetadata? {
        val nowInstant = now()
        val nowTs = Timestamp.from(nowInstant)
        val updated = jdbcTemplate.update(
            """
            UPDATE artifacts
               SET legal_hold = ?, legal_hold_reason = ?, legal_hold_set_at = ?, updated_at = ?
             WHERE organization_id = ? AND workstream_id = ? AND artifact_id = ?
            """.trimIndent(),
            legalHold,
            if (legalHold) reason else null,
            if (legalHold) nowTs else null,
            nowTs,
            scope.organizationId,
            scope.workstreamId,
            artifactId,
        )
        if (updated == 0) return null
        val row = selectRow(scope, artifactId) ?: return null
        return ArtifactGovernance.refreshArtifactMetadata(toMetadata(row), nowInstant)
    }

    override fun collectOrphanedUploads(scope: TenantScope): List<String> {
        val keys = blobClient.listObjectKeys("$uploadPrefix/")
        val reclaimed = mutableListOf<String>()
        for (key in keys) {
            if (bestEffortDelete(key)) reclaimed.add(key)
        }
        return reclaimed
    }

    override fun listGcMetadataRows(scope: TenantScope): List<ArtifactGcMetadataRow> =
        jdbcTemplate.query(
            """
            SELECT artifact_id, storage_key, availability_status
              FROM artifacts
             WHERE organization_id = ? AND workstream_id = ?
            """.trimIndent(),
            { rs, _ ->
                ArtifactGcMetadataRow(
                    artifactId = rs.getString("artifact_id"),
                    storageKey = rs.getString("storage_key"),
                    availabilityStatus = toAvailabilityStatus(rs.getString("availability_status")),
                )
            },
            scope.organizationId,
            scope.workstreamId,
        )

    private fun destroy(scope: TenantScope, artifactId: String, reason: String): Boolean {
        val row = selectRow(scope, artifactId) ?: return false
        val nowInstant = now()
        val metadata = ArtifactGovernance.refreshArtifactMetadata(toMetadata(row), nowInstant)
        if (!ArtifactGovernance.isArtifactDestroyable(metadata, nowInstant)) return false
        val purged = purgeRow(scope, artifactId, reason, nowInstant)
        if (!purged) return false
        bestEffortDelete(row.storageKey)
        return true
    }

    private fun saveMetadata(scope: TenantScope, metadata: ArtifactMetadata, storageKey: String) {
        val nowTs = Timestamp.from(metadata.createdAt)
        val payload = objectMapper.writeValueAsString(
            linkedMapOf<String, Any?>().apply {
                put("owner", metadata.owner)
                metadata.retentionDays?.let { put("retentionDays", it) }
            },
        )
        jdbcTemplate.update(
            """
            INSERT INTO artifacts (
              organization_id, workstream_id, namespace_id, workflow_id, artifact_id,
              availability_status, retention_status, legal_hold, retention_until, purged_at,
              purge_reason, legal_hold_reason, legal_hold_set_at, content_hash, size,
              content_type, storage_key, payload, created_at, updated_at
            ) VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?::jsonb, ?, ?)
            ON CONFLICT (organization_id, workstream_id, namespace_id, workflow_id, artifact_id)
            DO UPDATE SET
              availability_status = EXCLUDED.availability_status,
              retention_status = EXCLUDED.retention_status,
              legal_hold = EXCLUDED.legal_hold,
              retention_until = EXCLUDED.retention_until,
              purged_at = EXCLUDED.purged_at,
              purge_reason = EXCLUDED.purge_reason,
              legal_hold_reason = EXCLUDED.legal_hold_reason,
              legal_hold_set_at = EXCLUDED.legal_hold_set_at,
              content_hash = EXCLUDED.content_hash,
              size = EXCLUDED.size,
              content_type = EXCLUDED.content_type,
              storage_key = EXCLUDED.storage_key,
              payload = EXCLUDED.payload,
              updated_at = EXCLUDED.updated_at
            """.trimIndent(),
            scope.organizationId,
            scope.workstreamId,
            scopeFor(scope, metadata.owner).namespaceId,
            scopeFor(scope, metadata.owner).workflowId,
            metadata.id,
            metadata.availabilityStatus.wireValue,
            metadata.retentionStatus.wireValue,
            metadata.legalHold,
            metadata.retentionUntil?.let { Timestamp.from(it) },
            metadata.purgedAt?.let { Timestamp.from(it) },
            metadata.purgeReason,
            metadata.legalHoldReason,
            metadata.legalHoldSetAt?.let { Timestamp.from(it) },
            metadata.hash,
            metadata.size,
            metadata.contentType,
            storageKey,
            payload,
            nowTs,
            nowTs,
        )
    }

    private fun purgeRow(scope: TenantScope, artifactId: String, reason: String, nowInstant: Instant): Boolean {
        val nowTs = Timestamp.from(nowInstant)
        val updated = jdbcTemplate.update(
            """
            UPDATE artifacts
               SET availability_status = 'purged', purged_at = ?, purge_reason = ?, updated_at = ?
             WHERE organization_id = ? AND workstream_id = ? AND artifact_id = ?
               AND legal_hold = FALSE AND availability_status <> 'purged'
            """.trimIndent(),
            nowTs,
            reason,
            nowTs,
            scope.organizationId,
            scope.workstreamId,
            artifactId,
        )
        return updated > 0
    }

    private fun selectRow(scope: TenantScope, artifactId: String): ArtifactRow? {
        val rows = jdbcTemplate.query(
            """
            SELECT organization_id, workstream_id, namespace_id, workflow_id, artifact_id,
                   availability_status, retention_status, legal_hold, retention_until, purged_at,
                   purge_reason, legal_hold_reason, legal_hold_set_at, content_hash, size,
                   content_type, storage_key, payload::text AS payload, created_at, updated_at
              FROM artifacts
             WHERE organization_id = ? AND workstream_id = ? AND artifact_id = ?
            """.trimIndent(),
            ROW_MAPPER,
            scope.organizationId,
            scope.workstreamId,
            artifactId,
        )
        return rows.firstOrNull()
    }

    private fun toMetadata(row: ArtifactRow): ArtifactMetadata {
        val payload = parsePayload(row.payloadJson)
        val owner = (payload["owner"] as? String) ?: row.namespaceId
        val retentionDays = (payload["retentionDays"] as? Number)?.toInt()
        return ArtifactMetadata(
            id = row.artifactId,
            owner = owner,
            hash = row.contentHash,
            size = row.size,
            contentType = row.contentType,
            availabilityStatus = row.availabilityStatus,
            retentionStatus = toRetentionStatus(row.retentionStatus),
            legalHold = row.legalHold,
            createdAt = row.createdAt,
            retentionDays = retentionDays,
            retentionUntil = row.retentionUntil,
            purgedAt = row.purgedAt,
            purgeReason = row.purgeReason,
            legalHoldReason = row.legalHoldReason,
            legalHoldSetAt = row.legalHoldSetAt,
        )
    }

    private fun parsePayload(json: String?): Map<String, Any?> {
        if (json.isNullOrBlank()) return emptyMap()
        return try {
            @Suppress("UNCHECKED_CAST")
            objectMapper.readValue(json, Map::class.java) as Map<String, Any?>
        } catch (_: Exception) {
            emptyMap()
        }
    }

    private fun bestEffortDelete(key: String): Boolean =
        try {
            blobClient.deleteObject(key)
        } catch (_: Exception) {
            false
        }

    private fun contentKey(hash: String): String = "$objectPrefix/${ArtifactHash.digest(hash)}"

    companion object {
        const val DEFAULT_UPLOAD_PREFIX = "uploads"
        const val DEFAULT_OBJECT_PREFIX = "objects"
        const val DEFAULT_NAMESPACE_ID = "default"
        const val DEFAULT_WORKFLOW_ID = "default"

        /**
         * Derives the namespace / workflow scope from an owner structured as
         * `namespace/workflow`. A bare owner is used as the namespace and the
         * workflow defaults to [DEFAULT_WORKFLOW_ID].
         */
        fun scopeFor(scope: TenantScope, owner: String): ArtifactRowScope = parseOwnerScope(owner, scope)

        private fun parseOwnerScope(owner: String, scope: TenantScope): ArtifactRowScope {
            if (owner.isEmpty()) {
                return ArtifactRowScope(scope.organizationId, scope.workstreamId, DEFAULT_NAMESPACE_ID, DEFAULT_WORKFLOW_ID)
            }
            val separator = owner.indexOf('/')
            return if (separator > 0 && separator < owner.length - 1) {
                ArtifactRowScope(
                    scope.organizationId,
                    scope.workstreamId,
                    owner.substring(0, separator),
                    owner.substring(separator + 1),
                )
            } else {
                ArtifactRowScope(scope.organizationId, scope.workstreamId, owner, DEFAULT_WORKFLOW_ID)
            }
        }

        private fun toAvailabilityStatus(value: String?): ArtifactAvailabilityStatus = when (value) {
            "available" -> ArtifactAvailabilityStatus.AVAILABLE
            "purged" -> ArtifactAvailabilityStatus.PURGED
            "pending" -> ArtifactAvailabilityStatus.PENDING
            "uploading" -> ArtifactAvailabilityStatus.UPLOADING
            "unavailable" -> ArtifactAvailabilityStatus.UNAVAILABLE
            else -> ArtifactAvailabilityStatus.ARCHIVED
        }

        private fun toRetentionStatus(value: String?): ArtifactRetentionStatus =
            if (value == ArtifactRetentionStatus.EXPIRED.wireValue) {
                ArtifactRetentionStatus.EXPIRED
            } else {
                ArtifactRetentionStatus.ACTIVE
            }

        private val ROW_MAPPER = RowMapper { rs: ResultSet, _: Int ->
            ArtifactRow(
                organizationId = rs.getString("organization_id"),
                workstreamId = rs.getString("workstream_id"),
                namespaceId = rs.getString("namespace_id"),
                workflowId = rs.getString("workflow_id"),
                artifactId = rs.getString("artifact_id"),
                availabilityStatus = toAvailabilityStatus(rs.getString("availability_status")),
                retentionStatus = rs.getString("retention_status"),
                legalHold = rs.getBoolean("legal_hold"),
                retentionUntil = rs.getTimestamp("retention_until")?.toInstant(),
                purgedAt = rs.getTimestamp("purged_at")?.toInstant(),
                purgeReason = rs.getString("purge_reason"),
                legalHoldReason = rs.getString("legal_hold_reason"),
                legalHoldSetAt = rs.getTimestamp("legal_hold_set_at")?.toInstant(),
                contentHash = rs.getString("content_hash"),
                size = rs.getLong("size"),
                contentType = rs.getString("content_type"),
                storageKey = rs.getString("storage_key"),
                payloadJson = rs.getString("payload"),
                createdAt = rs.getTimestamp("created_at").toInstant(),
                updatedAt = rs.getTimestamp("updated_at").toInstant(),
            )
        }
    }
}

/** Full tenant / hierarchy scope of an artifact row. */
data class ArtifactRowScope(
    val organizationId: String,
    val workstreamId: String,
    val namespaceId: String,
    val workflowId: String,
)
