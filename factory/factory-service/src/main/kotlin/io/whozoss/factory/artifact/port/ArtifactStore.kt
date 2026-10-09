package io.whozoss.factory.artifact.port

import io.whozoss.factory.artifact.domain.ArtifactAvailabilityStatus
import io.whozoss.factory.artifact.domain.ArtifactMetadata
import io.whozoss.factory.persistence.TenantScope
import java.io.InputStream

/** Input accepted by [ArtifactStore.putArtifact]. */
data class PutArtifactParams(
    /** Owning principal of the artifact. */
    val owner: String,
    /** IANA media type of the payload. */
    val contentType: String,
    /** Raw payload bytes. */
    val data: ByteArray,
    /** Optional retention window, in days, starting at creation. */
    val retentionDays: Int? = null,
)

/** Result returned by [ArtifactStore.openArtifact]. */
data class OpenArtifactResult(
    /** Payload bytes as an input stream. */
    val stream: InputStream,
    /** Metadata of the opened artifact. */
    val metadata: ArtifactMetadata,
)

/**
 * Storage-agnostic artifact store.
 *
 * Ported from `ArtifactStore` in
 * `factory/src/ports/artifact/artifact-store.ts`. Every operation is scoped by
 * the composite tenant identity [TenantScope] (`organizationId`, `workstreamId`)
 * so a repository can never touch another tenant's rows.
 */
interface ArtifactStore {

    /** Stores a payload and returns its metadata. */
    fun putArtifact(scope: TenantScope, params: PutArtifactParams): ArtifactMetadata

    /** Returns the metadata of an artifact, or `null` when unknown. */
    fun getArtifactMetadata(scope: TenantScope, artifactId: String): ArtifactMetadata?

    /** Opens the payload of an available artifact, or `null` when unavailable. */
    fun openArtifact(scope: TenantScope, artifactId: String): OpenArtifactResult?

    /** Deletes an artifact, unless a legal hold forbids it. */
    fun deleteArtifact(scope: TenantScope, artifactId: String, reason: String? = null): Boolean

    /** Purges an expired-retention artifact, unless a legal hold forbids it. */
    fun purgeArtifact(scope: TenantScope, artifactId: String, reason: String? = null): Boolean

    /** Sets or releases the legal hold of an artifact. */
    fun setLegalHold(
        scope: TenantScope,
        artifactId: String,
        legalHold: Boolean,
        reason: String? = null,
    ): ArtifactMetadata?

    /** Deletes staging objects left behind by interrupted uploads. */
    fun collectOrphanedUploads(scope: TenantScope): List<String>
}

/**
 * Minimal metadata-row projection used by the garbage-collection reconciler.
 *
 * The frozen [ArtifactStore] port exposes no listing, so the GC use case
 * depends on this *injected* capability instead of widening the port.
 */
data class ArtifactGcMetadataRow(
    val artifactId: String,
    val storageKey: String,
    val availabilityStatus: ArtifactAvailabilityStatus,
)

/** Structural capability: listing authoritative metadata rows for GC. */
interface ArtifactGcMetadataLister {
    fun listGcMetadataRows(scope: TenantScope): List<ArtifactGcMetadataRow>
}
