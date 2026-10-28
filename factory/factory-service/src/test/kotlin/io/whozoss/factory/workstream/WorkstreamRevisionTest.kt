package io.whozoss.factory.workstream

import io.whozoss.factory.workstream.domain.Workstream
import io.whozoss.factory.workstream.domain.WorkstreamStatus
import io.whozoss.factory.workstream.projection.WorkstreamRevision
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test
import java.time.Instant

/**
 * Unit tests of the pure [WorkstreamRevision] ETag function: deterministic,
 * sensitive to every part, and stable in shape.
 */
class WorkstreamRevisionTest {

    private val workstream = Workstream(
        organizationId = "org-test",
        workstreamId = "ws-test",
        name = "Test",
        status = WorkstreamStatus.ACTIVE,
        revision = 3,
        createdAt = Instant.parse("2026-01-01T00:00:00Z"),
        updatedAt = Instant.parse("2026-01-02T00:00:00Z"),
    )

    private val parts = listOf("activeWorkflows=2", "steps=1,0,1", "latest=2026-01-02T00:00:00Z")

    @Test
    fun `identical inputs produce an identical revision`() {
        assertThat(WorkstreamRevision.compute(workstream, parts))
            .isEqualTo(WorkstreamRevision.compute(workstream, parts))
    }

    @Test
    fun `the revision is a 16 hex-char string`() {
        val revision = WorkstreamRevision.compute(workstream, parts)
        assertThat(revision).hasSize(16)
        assertThat(revision).matches("^[0-9a-f]{16}$")
    }

    @Test
    fun `any state change produces a different revision`() {
        val base = WorkstreamRevision.compute(workstream, parts)

        assertThat(WorkstreamRevision.compute(workstream.copy(revision = 4), parts)).isNotEqualTo(base)
        assertThat(WorkstreamRevision.compute(workstream.copy(status = WorkstreamStatus.PAUSED), parts)).isNotEqualTo(base)
        assertThat(
            WorkstreamRevision.compute(workstream.copy(updatedAt = Instant.parse("2026-01-03T00:00:00Z")), parts),
        ).isNotEqualTo(base)
        assertThat(WorkstreamRevision.compute(workstream, parts + "attempts=1")).isNotEqualTo(base)
        assertThat(WorkstreamRevision.compute(workstream, listOf("activeWorkflows=3") + parts.drop(1))).isNotEqualTo(base)
    }
}
