package io.whozoss.factory.artifact.infrastructure.persistence

import io.whozoss.factory.artifact.domain.ArtifactAvailabilityStatus
import io.whozoss.factory.artifact.domain.ArtifactRetentionStatus
import org.springframework.data.neo4j.core.schema.Id
import org.springframework.data.neo4j.core.schema.Node
import java.time.Instant

/**
 * Spring Data Neo4j projection of the authoritative artifact metadata.
 *
 * Replaces the `artifacts` PostgreSQL row (internal to the persistence
 * adapter). The binary payload itself still lives in object storage,
 * content-addressed under `objects/<sha256-hex>`; only the metadata moves to the
 * `:ArtifactMetadata` node.
 *
 * The three governance dimensions remain orthogonal:
 * [availabilityStatus], [retentionStatus] and [legalHold].
 */
@Node("ArtifactMetadata")
data class ArtifactMetadataNode(
    @Id
    val id: String,
    val organizationId: String,
    val workstreamId: String,
    val namespaceId: String,
    val workflowId: String,
    val owner: String,
    val contentType: String,
    val contentHash: String,
    val size: Long,
    val storageKey: String,
    val availabilityStatus: String,
    val retentionStatus: String,
    val legalHold: Boolean,
    val retentionDays: Int? = null,
    val retentionUntil: Instant? = null,
    val purgedAt: Instant? = null,
    val purgeReason: String? = null,
    val legalHoldReason: String? = null,
    val legalHoldSetAt: Instant? = null,
    val createdAt: Instant = Instant.now(),
    val updatedAt: Instant = Instant.now(),
) {
    fun availability(): ArtifactAvailabilityStatus = ArtifactAvailabilityStatus.fromWire(availabilityStatus)

    fun retention(): ArtifactRetentionStatus = ArtifactRetentionStatus.fromWire(retentionStatus)
}
