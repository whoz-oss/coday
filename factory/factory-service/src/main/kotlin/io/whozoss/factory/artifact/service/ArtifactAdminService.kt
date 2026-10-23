package io.whozoss.factory.artifact.service

import com.fasterxml.jackson.annotation.JsonInclude
import io.whozoss.factory.artifact.domain.ArtifactAvailabilityStatus
import io.whozoss.factory.artifact.domain.ArtifactMetadata
import io.whozoss.factory.artifact.domain.ArtifactRetentionStatus
import io.whozoss.factory.artifact.error.ArtifactAdminException
import io.whozoss.factory.artifact.infrastructure.blob.ArtifactBlobClient
import io.whozoss.factory.artifact.port.ArtifactGcMetadataLister
import io.whozoss.factory.artifact.port.ArtifactStore
import io.whozoss.factory.persistence.TenantScope
import java.time.Instant

/** Terminal statuses of an admin purge attempt. */
object ArtifactAdminPurgeStatus {
    const val PURGED = "purged"
    const val NOT_FOUND = "NOT_FOUND"
    const val LEGAL_HOLD_ACTIVE = "LEGAL_HOLD_ACTIVE"
    const val RETENTION_ACTIVE = "RETENTION_ACTIVE"
}

/** Structured outcome of [ArtifactAdminService.purgeArtifactAdmin]. */
@JsonInclude(JsonInclude.Include.NON_NULL)
data class ArtifactAdminPurgeResult(
    val success: Boolean,
    val artifactId: String,
    val reason: String,
    val status: String,
    val metadata: ArtifactMetadata? = null,
)

/** Kinds of blob-store / metadata divergence audited by the GC use case. */
object ArtifactGcAnomalyType {
    const val BLOB_WITHOUT_PG_ROW = "blob_without_pg_row"
    const val PG_PURGED_OR_MISSING_BLOB = "pg_purged_or_missing_blob"
}

/** A single divergence between object storage and the authoritative rows. */
@JsonInclude(JsonInclude.Include.NON_NULL)
data class ArtifactGcAnomaly(
    val type: String,
    val details: String,
    val artifactId: String? = null,
    val storageKey: String? = null,
)

/** Structured report returned by [ArtifactAdminService.collectAndAuditGarbage]. */
data class ArtifactGarbageCollectionReport(
    val reclaimedStagingKeys: List<String>,
    val anomalies: List<ArtifactGcAnomaly>,
    val scannedBlobKeys: List<String>,
    val scannedMetadataRows: Int,
    val timestamp: String,
)

/**
 * Admin governance use cases for factory artifacts.
 *
 * Ported from `factory/src/application/artifact/artifact-admin-use-cases.ts`.
 * Three *explicit* admin operations, always triggered by a human/operator —
 * never by a timer:
 *
 *   1. [purgeArtifactAdmin] — destroys an artifact whose compliance retention
 *      window has expired, and only then.
 *   2. [setLegalHoldAdmin] — places or releases an explicit legal hold.
 *   3. [collectAndAuditGarbage] — reclaims orphaned staging uploads and audits
 *      the object store against the authoritative metadata rows.
 */
class ArtifactAdminService(
    private val store: ArtifactStore,
    private val metadataLister: ArtifactGcMetadataLister,
    private val blobClient: ArtifactBlobClient,
    private val uploadPrefix: String = DEFAULT_UPLOAD_PREFIX,
    private val objectPrefix: String = DEFAULT_OBJECT_PREFIX,
    private val now: () -> Instant = Instant::now,
) {

    /**
     * Explicit admin purge of an artifact whose retention window has expired.
     *
     * Refuses — without throwing — when the artifact is unknown (`NOT_FOUND`),
     * under an active legal hold (`LEGAL_HOLD_ACTIVE`) or still within its
     * retention window (`RETENTION_ACTIVE`).
     */
    fun purgeArtifactAdmin(scope: TenantScope, artifactId: String, reason: String): ArtifactAdminPurgeResult {
        val metadata = store.getArtifactMetadata(scope, artifactId)
            ?: return ArtifactAdminPurgeResult(false, artifactId, reason, ArtifactAdminPurgeStatus.NOT_FOUND)
        if (metadata.legalHold) {
            return ArtifactAdminPurgeResult(false, artifactId, reason, ArtifactAdminPurgeStatus.LEGAL_HOLD_ACTIVE)
        }
        if (metadata.retentionStatus == ArtifactRetentionStatus.ACTIVE) {
            return ArtifactAdminPurgeResult(false, artifactId, reason, ArtifactAdminPurgeStatus.RETENTION_ACTIVE)
        }

        val purged = store.purgeArtifact(scope, artifactId, reason)
        if (!purged) {
            val latest = store.getArtifactMetadata(scope, artifactId)
                ?: return ArtifactAdminPurgeResult(false, artifactId, reason, ArtifactAdminPurgeStatus.NOT_FOUND)
            val status = if (latest.legalHold) {
                ArtifactAdminPurgeStatus.LEGAL_HOLD_ACTIVE
            } else {
                ArtifactAdminPurgeStatus.RETENTION_ACTIVE
            }
            return ArtifactAdminPurgeResult(false, artifactId, reason, status)
        }

        val refreshed = store.getArtifactMetadata(scope, artifactId)
        val metadataAfterPurge = refreshed
            ?: metadata.copy(availabilityStatus = ArtifactAvailabilityStatus.PURGED)
        return ArtifactAdminPurgeResult(
            success = true,
            artifactId = artifactId,
            reason = reason,
            status = ArtifactAdminPurgeStatus.PURGED,
            metadata = metadataAfterPurge,
        )
    }

    /**
     * Explicit admin action placing or releasing a legal hold. Throws
     * [ArtifactAdminException] (`ARTIFACT_NOT_FOUND`) when the artifact does not
     * exist.
     */
    fun setLegalHoldAdmin(scope: TenantScope, artifactId: String, legalHold: Boolean, reason: String?): ArtifactMetadata =
        store.setLegalHold(scope, artifactId, legalHold, reason)
            ?: throw ArtifactAdminException(
                errorCode = "ARTIFACT_NOT_FOUND",
                statusCode = 404,
                message = "Artifact $artifactId not found",
            )

    /** Convenience alias used by the controller. */
    fun purge(scope: TenantScope, artifactId: String, reason: String): ArtifactAdminPurgeResult =
        purgeArtifactAdmin(scope, artifactId, reason)

    /** Convenience alias used by the controller. */
    fun setLegalHold(scope: TenantScope, artifactId: String, legalHold: Boolean, reason: String?): ArtifactMetadata =
        setLegalHoldAdmin(scope, artifactId, legalHold, reason)

    /**
     * Explicit, operator-triggered garbage collection with anomaly audit.
     *
     * Reclaims staging uploads (`uploads/`), then reconciles the
     * content-addressed blobs (`objects/`) against the authoritative metadata
     * rows.
     */
    fun collectAndAuditGarbage(scope: TenantScope): ArtifactGarbageCollectionReport {
        val reclaimedStagingKeys = store.collectOrphanedUploads(scope)
        val scannedBlobKeys = blobClient.listObjectKeys("$objectPrefix/")
        val metadataRows = metadataLister.listGcMetadataRows(scope)

        val blobKeySet = scannedBlobKeys.toSet()
        val referencedKeys = metadataRows.map { it.storageKey }.toSet()
        val anomalies = mutableListOf<ArtifactGcAnomaly>()

        for (storageKey in scannedBlobKeys) {
            if (storageKey !in referencedKeys) {
                anomalies.add(
                    ArtifactGcAnomaly(
                        type = ArtifactGcAnomalyType.BLOB_WITHOUT_PG_ROW,
                        details = "Blob $storageKey has no authoritative metadata row",
                        storageKey = storageKey,
                    ),
                )
            }
        }

        for (row in metadataRows) {
            if (row.availabilityStatus == ArtifactAvailabilityStatus.PURGED) {
                anomalies.add(
                    ArtifactGcAnomaly(
                        type = ArtifactGcAnomalyType.PG_PURGED_OR_MISSING_BLOB,
                        details = "Metadata row ${row.artifactId} is marked purged",
                        artifactId = row.artifactId,
                        storageKey = row.storageKey,
                    ),
                )
            } else if (row.storageKey !in blobKeySet) {
                anomalies.add(
                    ArtifactGcAnomaly(
                        type = ArtifactGcAnomalyType.PG_PURGED_OR_MISSING_BLOB,
                        details = "Blob ${row.storageKey} for artifact ${row.artifactId} is missing from object storage",
                        artifactId = row.artifactId,
                        storageKey = row.storageKey,
                    ),
                )
            }
        }

        return ArtifactGarbageCollectionReport(
            reclaimedStagingKeys = reclaimedStagingKeys,
            anomalies = anomalies,
            scannedBlobKeys = scannedBlobKeys,
            scannedMetadataRows = metadataRows.size,
            timestamp = now().toString(),
        )
    }

    companion object {
        const val DEFAULT_UPLOAD_PREFIX = "uploads"
        const val DEFAULT_OBJECT_PREFIX = "objects"
    }
}
