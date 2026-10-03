package io.whozoss.factory.artifact.infrastructure.persistence

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
import java.time.Instant

/**
 * Composed Neo4j + object-storage implementation of [ArtifactStore].
 *
 * Replaces `PostgresArtifactStore`. The immutable binary payload still lives in
 * object storage, content-addressed under `objects/<sha256-hex>`; the
 * authoritative metadata (statuses, retention window, legal hold and purge
 * record) now lives in an `:ArtifactMetadata` node.
 *
 * `putArtifact` keeps the *upload-then-commit* protocol: the payload is uploaded
 * to a staging key `uploads/<id>.part`, promoted to its content-addressed key,
 * verified in object storage, and only then is the metadata node committed. A
 * failed verification or commit therefore leaves no authoritative node — only a
 * reclaimable orphan in object storage.
 */
class Neo4jArtifactStore(
    private val repository: SpringDataNeo4jArtifactRepository,
    private val blobClient: ArtifactBlobClient,
    private val defaultRetentionDays: Int = ArtifactGovernance.DEFAULT_RETENTION_DAYS,
    private val uploadPrefix: String = DEFAULT_UPLOAD_PREFIX,
    private val objectPrefix: String = DEFAULT_OBJECT_PREFIX,
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
        // 4. Commit the authoritative metadata node in Neo4j.
        saveMetadata(scope, metadata, contentKey)
        // 5. Best-effort removal of the staging object.
        bestEffortDelete(stagingKey)
        return metadata
    }

    override fun getArtifactMetadata(scope: TenantScope, artifactId: String): ArtifactMetadata? {
        val node = findNode(scope, artifactId) ?: return null
        return ArtifactGovernance.refreshArtifactMetadata(toMetadata(node), now())
    }

    override fun openArtifact(scope: TenantScope, artifactId: String): OpenArtifactResult? {
        val node = findNode(scope, artifactId) ?: return null
        val metadata = ArtifactGovernance.refreshArtifactMetadata(toMetadata(node), now())
        if (metadata.availabilityStatus == ArtifactAvailabilityStatus.PURGED) return null
        val stream = blobClient.getObject(node.storageKey) ?: return null
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
        val node = findNode(scope, artifactId) ?: return null
        val nowInstant = now()
        val updated = node.copy(
            legalHold = legalHold,
            legalHoldReason = if (legalHold) reason else null,
            legalHoldSetAt = if (legalHold) nowInstant else null,
            updatedAt = nowInstant,
        )
        repository.save(updated)
        return ArtifactGovernance.refreshArtifactMetadata(toMetadata(updated), nowInstant)
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
        repository.findAllByScope(scope.organizationId, scope.workstreamId).map { node ->
            ArtifactGcMetadataRow(
                artifactId = node.id,
                storageKey = node.storageKey,
                availabilityStatus = node.availability(),
            )
        }

    private fun destroy(scope: TenantScope, artifactId: String, reason: String): Boolean {
        val node = findNode(scope, artifactId) ?: return false
        val nowInstant = now()
        val metadata = ArtifactGovernance.refreshArtifactMetadata(toMetadata(node), nowInstant)
        if (!ArtifactGovernance.isArtifactDestroyable(metadata, nowInstant)) return false
        val purged = node.copy(
            availabilityStatus = ArtifactAvailabilityStatus.PURGED.wireValue,
            purgedAt = nowInstant,
            purgeReason = reason,
            updatedAt = nowInstant,
        )
        repository.save(purged)
        bestEffortDelete(node.storageKey)
        return true
    }

    private fun findNode(scope: TenantScope, artifactId: String): ArtifactMetadataNode? =
        repository
            .findById(artifactId)
            .orElse(null)
            ?.takeIf { it.organizationId == scope.organizationId && it.workstreamId == scope.workstreamId }

    private fun saveMetadata(scope: TenantScope, metadata: ArtifactMetadata, storageKey: String) {
        val rowScope = scopeFor(scope, metadata.owner)
        repository.save(
            ArtifactMetadataNode(
                id = metadata.id,
                organizationId = scope.organizationId,
                workstreamId = scope.workstreamId,
                namespaceId = rowScope.namespaceId,
                workflowId = rowScope.workflowId,
                owner = metadata.owner,
                contentType = metadata.contentType,
                contentHash = metadata.hash,
                size = metadata.size,
                storageKey = storageKey,
                availabilityStatus = metadata.availabilityStatus.wireValue,
                retentionStatus = metadata.retentionStatus.wireValue,
                legalHold = metadata.legalHold,
                retentionDays = metadata.retentionDays,
                retentionUntil = metadata.retentionUntil,
                purgedAt = metadata.purgedAt,
                purgeReason = metadata.purgeReason,
                legalHoldReason = metadata.legalHoldReason,
                legalHoldSetAt = metadata.legalHoldSetAt,
                createdAt = metadata.createdAt,
                updatedAt = metadata.createdAt,
            ),
        )
    }

    private fun toMetadata(node: ArtifactMetadataNode): ArtifactMetadata =
        ArtifactMetadata(
            id = node.id,
            owner = node.owner,
            hash = node.contentHash,
            size = node.size,
            contentType = node.contentType,
            availabilityStatus = node.availability(),
            retentionStatus = node.retention(),
            legalHold = node.legalHold,
            createdAt = node.createdAt,
            retentionDays = node.retentionDays,
            retentionUntil = node.retentionUntil,
            purgedAt = node.purgedAt,
            purgeReason = node.purgeReason,
            legalHoldReason = node.legalHoldReason,
            legalHoldSetAt = node.legalHoldSetAt,
        )

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
    }
}

/** Full tenant / hierarchy scope of an artifact node. */
data class ArtifactRowScope(
    val organizationId: String,
    val workstreamId: String,
    val namespaceId: String,
    val workflowId: String,
)
