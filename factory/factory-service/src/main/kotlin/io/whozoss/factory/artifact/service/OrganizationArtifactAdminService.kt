package io.whozoss.factory.artifact.service

import io.whozoss.factory.artifact.domain.ArtifactMetadata
import io.whozoss.factory.artifact.error.ArtifactAdminException
import io.whozoss.factory.artifact.infrastructure.persistence.SpringDataNeo4jArtifactRepository
import io.whozoss.factory.persistence.OrganizationScope
import io.whozoss.factory.persistence.TenantScope
import org.springframework.stereotype.Service

/** Resolves artifact ownership inside the authenticated organization before governance operations. */
@Service
class OrganizationArtifactAdminService(
    private val repository: SpringDataNeo4jArtifactRepository,
    private val admin: ArtifactAdminService,
    private val blobs: io.whozoss.factory.artifact.infrastructure.blob.ArtifactBlobClient,
    private val properties: io.whozoss.factory.artifact.config.ArtifactProperties,
) {
    private fun owner(scope: OrganizationScope, artifactId: String): TenantScope? =
        repository.findInOrganization(scope.organizationId, artifactId)?.let {
            TenantScope(scope.organizationId, it.workstreamId)
        }

    fun purgeArtifactAdmin(scope: OrganizationScope, artifactId: String, reason: String): ArtifactAdminPurgeResult {
        val owner = owner(scope, artifactId)
            ?: return ArtifactAdminPurgeResult(false, artifactId, reason, ArtifactAdminPurgeStatus.NOT_FOUND)
        return admin.purgeArtifactAdmin(owner, artifactId, reason)
    }

    fun setLegalHoldAdmin(scope: OrganizationScope, artifactId: String, legalHold: Boolean, reason: String?): ArtifactMetadata {
        val owner = owner(scope, artifactId)
            ?: throw ArtifactAdminException("ARTIFACT_NOT_FOUND", 404, "Artifact not found")
        return admin.setLegalHoldAdmin(owner, artifactId, legalHold, reason)
    }

    fun collectAndAuditGarbage(scope: OrganizationScope): ArtifactGarbageCollectionReport {
        val now = java.time.Instant.now()
        val uploadPrefix = io.whozoss.factory.artifact.infrastructure.blob.ArtifactBlobKeys.organizationPrefix(properties.uploadPrefix, scope.organizationId)
        val objectPrefix = io.whozoss.factory.artifact.infrastructure.blob.ArtifactBlobKeys.organizationPrefix(properties.objectPrefix, scope.organizationId)
        // Staging is conservatively retained for 24h. Unknown/legacy keys are never deleted.
        val cutoff = now.minusSeconds(86_400).toEpochMilli()
        val reclaimed = blobs.listObjectKeys(uploadPrefix).filter { key ->
            val startedAt = key.removePrefix(uploadPrefix).substringBefore('/').toLongOrNull()
            startedAt != null && startedAt < cutoff && blobs.deleteObject(key)
        }
        val keys = blobs.listObjectKeys(objectPrefix)
        val rows = repository.findAllInOrganization(scope.organizationId)
        val references = rows.map { it.storageKey }.toSet()
        val anomalies = mutableListOf<ArtifactGcAnomaly>()
        keys.filter { it !in references }.forEach { key ->
            anomalies += ArtifactGcAnomaly(ArtifactGcAnomalyType.BLOB_WITHOUT_PG_ROW, "Object has no metadata in this organization", storageKey = key)
        }
        rows.forEach { row ->
            if (row.availability() == io.whozoss.factory.artifact.domain.ArtifactAvailabilityStatus.PURGED || !blobs.headObject(row.storageKey)) {
                anomalies += ArtifactGcAnomaly(ArtifactGcAnomalyType.PG_PURGED_OR_MISSING_BLOB, "Artifact is purged or its object is missing", artifactId = row.id, storageKey = row.storageKey)
            }
        }
        return ArtifactGarbageCollectionReport(reclaimed, anomalies, keys, rows.size, now.toString())
    }
}
