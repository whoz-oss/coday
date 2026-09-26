package io.whozoss.factory.artifact.infrastructure.persistence

import io.whozoss.factory.artifact.domain.ArtifactAvailabilityStatus

/**
 * Durable row shape of the V6 `artifacts` table (internal to the persistence
 * adapter). The domain [io.whozoss.factory.artifact.domain.ArtifactMetadata] is
 * derived from this by [PostgresArtifactStore].
 */
internal data class ArtifactRow(
    val organizationId: String,
    val workstreamId: String,
    val namespaceId: String,
    val workflowId: String,
    val artifactId: String,
    val availabilityStatus: ArtifactAvailabilityStatus,
    val retentionStatus: String,
    val legalHold: Boolean,
    val retentionUntil: java.time.Instant?,
    val purgedAt: java.time.Instant?,
    val purgeReason: String?,
    val legalHoldReason: String?,
    val legalHoldSetAt: java.time.Instant?,
    val contentHash: String,
    val size: Long,
    val contentType: String,
    val storageKey: String,
    val payloadJson: String,
    val createdAt: java.time.Instant,
    val updatedAt: java.time.Instant,
)
