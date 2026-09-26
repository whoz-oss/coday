package io.whozoss.factory.artifact.domain

import com.fasterxml.jackson.annotation.JsonInclude
import java.time.Instant

/**
 * Metadata describing a stored artifact.
 *
 * Ported from `ArtifactMetadata` in
 * `factory/src/ports/artifact/artifact-store.ts`. The three status dimensions are
 * deliberately **orthogonal** so a single artifact can be, for example, still
 * retained (`retentionStatus = ACTIVE`), under legal hold (`legalHold = true`)
 * and yet no longer readable by the runtime (`availabilityStatus = PURGED`).
 *
 * Optional fields are omitted from the serialized JSON when absent, matching the
 * Node conditional-spread output.
 */
@JsonInclude(JsonInclude.Include.NON_NULL)
data class ArtifactMetadata(
    /** Stable, content-independent identifier of the artifact. */
    val id: String,
    /** Owning principal (namespace, agent or user) of the artifact. */
    val owner: String,
    /** Content address: `sha256:<hex>` over the payload bytes. */
    val hash: String,
    /** Payload size in bytes. */
    val size: Long,
    /** IANA media type of the payload. */
    val contentType: String,
    /** Current physical availability of the payload. */
    val availabilityStatus: ArtifactAvailabilityStatus,
    /** Current compliance retention state. */
    val retentionStatus: ArtifactRetentionStatus,
    /** Whether an explicit legal hold currently forbids destruction. */
    val legalHold: Boolean,
    /** Creation timestamp. */
    val createdAt: Instant,
    /** Requested retention window, in days, from creation. */
    val retentionDays: Int? = null,
    /** Absolute end of the retention window, when a retention was requested. */
    val retentionUntil: Instant? = null,
    /** Destruction timestamp, set when the payload is purged. */
    val purgedAt: Instant? = null,
    /** Human-readable justification of the destruction. */
    val purgeReason: String? = null,
    /** Human-readable justification of the legal hold. */
    val legalHoldReason: String? = null,
    /** Timestamp at which the legal hold was last set. */
    val legalHoldSetAt: Instant? = null,
)
