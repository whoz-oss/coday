package io.whozoss.factory.artifact.domain

import java.time.Duration
import java.time.Instant

/**
 * Pure governance helpers shared by every artifact adapter.
 *
 * Faithful port of the retention / destruction rules in
 * `factory/src/adapters/artifact/memory-artifact-store.ts`:
 *
 *   - default retention is [DEFAULT_RETENTION_DAYS] days;
 *   - `retentionUntil = createdAt + retentionDays`;
 *   - retention is active while `retentionUntil > now`;
 *   - destruction is refused while a legal hold is active or the retention
 *     window is still open.
 */
object ArtifactGovernance {

    /** Default retention window, in days, when none is requested nor configured. */
    const val DEFAULT_RETENTION_DAYS = 90

    private val ONE_DAY: Duration = Duration.ofDays(1)

    /** Computes the absolute retention deadline, when a window was requested. */
    fun computeRetentionUntil(createdAt: Instant, retentionDays: Int?): Instant? =
        retentionDays?.let { createdAt.plus(ONE_DAY.multipliedBy(it.toLong())) }

    /** Evaluates whether the retention window is still open at [now]. */
    fun isRetentionActive(metadata: ArtifactMetadata, now: Instant): Boolean {
        val until = metadata.retentionUntil ?: return false
        return until.isAfter(now)
    }

    /** Recomputes the derived retention status of an artifact at [now]. */
    fun computeRetentionStatus(metadata: ArtifactMetadata, now: Instant): ArtifactRetentionStatus =
        if (isRetentionActive(metadata, now)) ArtifactRetentionStatus.ACTIVE else ArtifactRetentionStatus.EXPIRED

    /** Returns a copy of the metadata with its derived retention status refreshed. */
    fun refreshArtifactMetadata(metadata: ArtifactMetadata, now: Instant): ArtifactMetadata =
        metadata.copy(retentionStatus = computeRetentionStatus(metadata, now))

    /**
     * Whether destruction is allowed at [now]: the payload must still be
     * available, no legal hold may be active, and the retention window must have
     * closed (or never been opened).
     */
    fun isArtifactDestroyable(metadata: ArtifactMetadata, now: Instant): Boolean {
        if (metadata.availabilityStatus == ArtifactAvailabilityStatus.PURGED) return false
        if (metadata.legalHold) return false
        return !isRetentionActive(metadata, now)
    }

    /** Derives the initial metadata of a freshly stored artifact. */
    fun buildArtifactMetadata(
        id: String,
        owner: String,
        contentType: String,
        data: ByteArray,
        retentionDays: Int?,
        now: Instant,
    ): ArtifactMetadata {
        val retentionUntil = computeRetentionUntil(now, retentionDays)
        val metadata = ArtifactMetadata(
            id = id,
            owner = owner,
            hash = ArtifactHash.compute(data),
            size = data.size.toLong(),
            contentType = contentType,
            availabilityStatus = ArtifactAvailabilityStatus.AVAILABLE,
            retentionStatus = if (retentionUntil != null) {
                ArtifactRetentionStatus.ACTIVE
            } else {
                ArtifactRetentionStatus.EXPIRED
            },
            legalHold = false,
            createdAt = now,
            retentionDays = retentionDays,
            retentionUntil = retentionUntil,
        )
        return refreshArtifactMetadata(metadata, now)
    }
}
