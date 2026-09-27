package io.whozoss.factory.agentattempt

import io.whozoss.factory.DomainIntegrationTest
import io.whozoss.factory.agentattempt.service.OutboxDrainService
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test
import org.springframework.beans.factory.annotation.Autowired
import java.sql.Timestamp
import java.time.Instant

/**
 * Testcontainers integration tests of [OutboxDrainService] (V4
 * `outbox_events`) against a real PostgreSQL instance.
 *
 * Extends [DomainIntegrationTest] so the whole suite shares the single cached
 * Spring context. Skipped gracefully when no Docker daemon is available.
 */
class OutboxDrainServiceIntegrationTest : DomainIntegrationTest() {

    @Autowired
    private lateinit var drain: OutboxDrainService

    @Test
    fun `drains pending events and marks them dispatched`() {
        insertEvent("evt-1", "pending", Instant.parse("2026-05-01T00:00:01Z"))
        insertEvent("evt-2", "pending", Instant.parse("2026-05-01T00:00:02Z"))

        val report = drain.drainPending(ORGANIZATION_ID, limit = 10)

        assertThat(report.dispatched).isEqualTo(2)
        assertThat(report.failed).isEqualTo(0)
        assertThat(statusOf("evt-1")).isEqualTo("dispatched")
        assertThat(statusOf("evt-2")).isEqualTo("dispatched")
        assertThat(
            jdbcTemplate.queryForObject(
                "SELECT dispatched_at FROM outbox_events WHERE organization_id = ? AND id = ?",
                Timestamp::class.java,
                ORGANIZATION_ID,
                "evt-1",
            ),
        ).isNotNull()
    }

    @Test
    fun `a failing handler marks the event failed and increments attempts`() {
        insertEvent("evt-bad", "pending", Instant.parse("2026-05-01T00:00:01Z"))

        val report = drain.drainPending(ORGANIZATION_ID, limit = 10) { throw IllegalStateException("ENGINE_FAILURE") }

        assertThat(report.dispatched).isEqualTo(0)
        assertThat(report.failed).isEqualTo(1)
        assertThat(statusOf("evt-bad")).isEqualTo("failed")
        assertThat(
            jdbcTemplate.queryForObject(
                "SELECT attempts FROM outbox_events WHERE organization_id = ? AND id = ?",
                Int::class.javaObjectType,
                ORGANIZATION_ID,
                "evt-bad",
            ),
        ).isEqualTo(1)
    }

    @Test
    fun `only pending events are drained and the limit is honoured`() {
        insertEvent("pending-1", "pending", Instant.parse("2026-05-01T00:00:01Z"))
        insertEvent("dispatched-1", "dispatched", Instant.parse("2026-05-01T00:00:02Z"))
        insertEvent("pending-2", "pending", Instant.parse("2026-05-01T00:00:03Z"))

        val report = drain.drainPending(ORGANIZATION_ID, limit = 1)

        assertThat(report.dispatched).isEqualTo(1)
        assertThat(statusOf("pending-1")).isEqualTo("dispatched")
        assertThat(statusOf("pending-2")).isEqualTo("pending")
        assertThat(statusOf("dispatched-1")).isEqualTo("dispatched")
    }

    private fun insertEvent(id: String, status: String, createdAt: Instant) {
        jdbcTemplate.update(
            """
            INSERT INTO outbox_events (
                organization_id, id, workstream_id, event_type, payload, status, created_at
            ) VALUES (?, ?, ?, ?, '{}'::jsonb, ?, ?)
            """.trimIndent(),
            ORGANIZATION_ID,
            id,
            WORKSTREAM_ID,
            "result_submitted",
            status,
            Timestamp.from(createdAt),
        )
    }

    private fun statusOf(id: String): String? =
        jdbcTemplate.queryForObject(
            "SELECT status FROM outbox_events WHERE organization_id = ? AND id = ?",
            String::class.java,
            ORGANIZATION_ID,
            id,
        )
}
