package io.whozoss.factory.artifact.domain

import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test
import java.time.Instant

/**
 * Pure unit tests for the artifact governance rules. No Spring, no Docker: they
 * pin the retention window, the orthogonal status derivation and the destruction
 * refusal contract ported from the Node in-memory adapter.
 */
class ArtifactDomainTest {

    private val now: Instant = Instant.parse("2026-01-01T00:00:00Z")

    private fun metadata(
        retentionDays: Int? = 30,
        retentionUntil: Instant? = now.plusSeconds(30L * 86_400L),
        legalHold: Boolean = false,
        availabilityStatus: ArtifactAvailabilityStatus = ArtifactAvailabilityStatus.AVAILABLE,
    ): ArtifactMetadata = ArtifactMetadata(
        id = "art-1",
        owner = "ns",
        hash = "sha256:abc",
        size = 3,
        contentType = "text/plain",
        availabilityStatus = availabilityStatus,
        retentionStatus = ArtifactRetentionStatus.ACTIVE,
        legalHold = legalHold,
        createdAt = now,
        retentionDays = retentionDays,
        retentionUntil = retentionUntil,
    )

    @Test
    fun `default retention is ninety days`() {
        assertThat(ArtifactGovernance.DEFAULT_RETENTION_DAYS).isEqualTo(90)
    }

    @Test
    fun `retention until is createdAt plus retentionDays`() {
        assertThat(ArtifactGovernance.computeRetentionUntil(now, 30))
            .isEqualTo(Instant.parse("2026-01-31T00:00:00Z"))
        assertThat(ArtifactGovernance.computeRetentionUntil(now, 0)).isEqualTo(now)
        assertThat(ArtifactGovernance.computeRetentionUntil(now, null)).isNull()
    }

    @Test
    fun `retention status is active while the window is open`() {
        val active = metadata(retentionUntil = now.plusSeconds(30L * 86_400L))
        assertThat(ArtifactGovernance.computeRetentionStatus(active, now)).isEqualTo(ArtifactRetentionStatus.ACTIVE)

        val afterWindow = now.plusSeconds(31L * 86_400L)
        assertThat(ArtifactGovernance.computeRetentionStatus(active, afterWindow))
            .isEqualTo(ArtifactRetentionStatus.EXPIRED)
    }

    @Test
    fun `zero day retention is immediately expired`() {
        val built = ArtifactGovernance.buildArtifactMetadata(
            id = "art-0",
            owner = "ns",
            contentType = "text/plain",
            data = "x".toByteArray(),
            retentionDays = 0,
            now = now,
        )
        assertThat(built.retentionUntil).isEqualTo(now)
        assertThat(built.retentionStatus).isEqualTo(ArtifactRetentionStatus.EXPIRED)
        assertThat(built.availabilityStatus).isEqualTo(ArtifactAvailabilityStatus.AVAILABLE)
        assertThat(built.legalHold).isFalse()
    }

    @Test
    fun `destruction is refused for active retention or legal hold`() {
        val activeRetention = metadata(retentionUntil = now.plusSeconds(86_400L))
        assertThat(ArtifactGovernance.isArtifactDestroyable(activeRetention, now)).isFalse()

        val legalHold = metadata(retentionUntil = now.minusSeconds(1), legalHold = true)
        assertThat(ArtifactGovernance.isArtifactDestroyable(legalHold, now)).isFalse()

        val expired = metadata(retentionUntil = now.minusSeconds(1))
        assertThat(ArtifactGovernance.isArtifactDestroyable(expired, now)).isTrue()

        val purged = metadata(
            retentionUntil = now.minusSeconds(1),
            availabilityStatus = ArtifactAvailabilityStatus.PURGED,
        )
        assertThat(ArtifactGovernance.isArtifactDestroyable(purged, now)).isFalse()
    }

    @Test
    fun `hash is content addressed with the sha256 prefix`() {
        val hash = ArtifactHash.compute("payload".toByteArray())
        assertThat(hash).startsWith("sha256:")
        assertThat(ArtifactHash.digest(hash)).hasSize(64)
        assertThat(ArtifactHash.compute("payload".toByteArray())).isEqualTo(hash)
        assertThat(ArtifactHash.compute("other".toByteArray())).isNotEqualTo(hash)
    }

    @Test
    fun `createArtifactId returns a unique uuid`() {
        assertThat(ArtifactHash.createArtifactId()).isNotEqualTo(ArtifactHash.createArtifactId())
    }
}
