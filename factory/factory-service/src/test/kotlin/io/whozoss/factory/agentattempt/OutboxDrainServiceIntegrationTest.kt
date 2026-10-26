package io.whozoss.factory.agentattempt

import io.whozoss.factory.Neo4jDomainIntegrationTest
import io.whozoss.factory.agentattempt.persistence.OutboxEventNode
import io.whozoss.factory.agentattempt.persistence.SpringDataNeo4jOutboxRepository
import io.whozoss.factory.agentattempt.service.OutboxDrainService
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test
import org.springframework.beans.factory.annotation.Autowired
import java.time.Instant

/**
 * Embedded-Neo4j integration tests of [OutboxDrainService] (the former V4
 * `outbox_events` table is now an `:OutboxEvent` node).
 *
 * Extends [Neo4jDomainIntegrationTest]: the engine is the in-process Neo4j test
 * harness, so no Docker is required.
 */
class OutboxDrainServiceIntegrationTest : Neo4jDomainIntegrationTest() {

    @Autowired
    private lateinit var drain: OutboxDrainService

    @Autowired
    private lateinit var outbox: SpringDataNeo4jOutboxRepository

    @Test
    fun `drains pending events and marks them dispatched`() {
        insertEvent("evt-1", "pending", Instant.parse("2026-05-01T00:00:01Z"))
        insertEvent("evt-2", "pending", Instant.parse("2026-05-01T00:00:02Z"))

        val report = drain.drainPending(ORGANIZATION_ID, limit = 10)

        assertThat(report.dispatched).isEqualTo(2)
        assertThat(report.failed).isEqualTo(0)
        assertThat(statusOf("evt-1")).isEqualTo("dispatched")
        assertThat(statusOf("evt-2")).isEqualTo("dispatched")
        assertThat(nodeOf("evt-1")?.dispatchedAt).isNotNull()
    }

    @Test
    fun `a failing handler marks the event failed and increments attempts`() {
        insertEvent("evt-bad", "pending", Instant.parse("2026-05-01T00:00:01Z"))

        val report = drain.drainPending(ORGANIZATION_ID, limit = 10) { throw IllegalStateException("ENGINE_FAILURE") }

        assertThat(report.dispatched).isEqualTo(0)
        assertThat(report.failed).isEqualTo(1)
        assertThat(statusOf("evt-bad")).isEqualTo("failed")
        assertThat(nodeOf("evt-bad")?.attempts).isEqualTo(1)
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

    @Test
    fun `pending organizations lists only tenants with pending events`() {
        insertEvent("evt-pending", "pending", Instant.parse("2026-05-01T00:00:01Z"))

        assertThat(drain.pendingOrganizations()).containsExactly(ORGANIZATION_ID)
    }

    private fun insertEvent(id: String, status: String, createdAt: Instant) {
        outbox.save(
            OutboxEventNode(
                id = OutboxEventNode.compositeId(ORGANIZATION_ID, id),
                organizationId = ORGANIZATION_ID,
                eventId = id,
                workstreamId = WORKSTREAM_ID,
                eventType = "result_submitted",
                payload = "{}",
                status = status,
                attempts = 0,
                createdAt = createdAt,
            ),
        )
    }

    private fun nodeOf(id: String): OutboxEventNode? =
        outbox.findById(OutboxEventNode.compositeId(ORGANIZATION_ID, id)).orElse(null)

    private fun statusOf(id: String): String? = nodeOf(id)?.status
}
