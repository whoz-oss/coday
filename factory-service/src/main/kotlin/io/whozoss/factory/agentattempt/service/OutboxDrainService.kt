package io.whozoss.factory.agentattempt.service

import mu.KotlinLogging
import org.springframework.jdbc.core.namedparam.MapSqlParameterSource
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate
import org.springframework.stereotype.Service
import org.springframework.transaction.annotation.Transactional
import java.sql.Timestamp
import java.time.Instant

/** A pending transactional-outbox event selected for dispatch. */
data class OutboxEvent(
    val id: String,
    val eventType: String,
    val payload: String,
    val attempts: Int,
    val workstreamId: String,
)

/** Outcome of one drain pass. */
data class DrainReport(
    val dispatched: Int,
    val failed: Int,
)

/**
 * SQL polling drain worker for the V4 `outbox_events` table.
 *
 * Deliberately uses **no** `LISTEN`/`NOTIFY`: it selects the oldest pending
 * events of a tenant (`WHERE organization_id = ? AND status = 'pending'
 * ORDER BY created_at LIMIT ? ... FOR UPDATE SKIP LOCKED`), then marks each one
 * `dispatched` on success or `failed` (incrementing `attempts`) when the handler
 * throws. A later pass can therefore retry or dead-letter the failed events.
 */
@Service
class OutboxDrainService(
    private val jdbc: NamedParameterJdbcTemplate,
) {

    private val logger = KotlinLogging.logger {}

    /**
     * The distinct organizations that currently have at least one `pending`
     * event. A background drain worker iterates these instead of scanning every
     * tenant, so an idle deployment does no work.
     */
    @Transactional(readOnly = true)
    fun pendingOrganizations(): List<String> =
        jdbc.queryForList(
            "SELECT DISTINCT organization_id FROM outbox_events WHERE status = 'pending'",
            emptyMap<String, Any>(),
            String::class.java,
        )

    /**
     * Drain up to [limit] pending events of [organizationId].
     *
     * [handler] receives each selected event; throwing marks that event
     * `failed`, returning normally marks it `dispatched`.
     */
    @Transactional
    fun drainPending(
        organizationId: String,
        limit: Int = 50,
        now: Instant = Instant.now(),
        handler: (OutboxEvent) -> Unit = {},
    ): DrainReport {
        val pending = selectPending(organizationId, limit)
        var dispatched = 0
        var failed = 0
        for (event in pending) {
            try {
                handler(event)
                markDispatched(organizationId, event.id, now)
                dispatched++
            } catch (failure: Exception) {
                logger.warn(failure) { "Outbox event ${event.id} failed to dispatch" }
                markFailed(organizationId, event.id)
                failed++
            }
        }
        return DrainReport(dispatched = dispatched, failed = failed)
    }

    private fun selectPending(organizationId: String, limit: Int): List<OutboxEvent> =
        jdbc.query(
            """
            SELECT id, workstream_id, event_type, payload, attempts
              FROM outbox_events
             WHERE organization_id = :organizationId
               AND status = 'pending'
             ORDER BY created_at ASC
             LIMIT :limit
             FOR UPDATE SKIP LOCKED
            """.trimIndent(),
            MapSqlParameterSource()
                .addValue("organizationId", organizationId)
                .addValue("limit", limit),
        ) { rs, _ ->
            OutboxEvent(
                id = rs.getString("id"),
                eventType = rs.getString("event_type"),
                payload = rs.getString("payload") ?: "{}",
                attempts = rs.getInt("attempts"),
                workstreamId = rs.getString("workstream_id") ?: "",
            )
        }

    private fun markDispatched(organizationId: String, id: String, now: Instant) {
        jdbc.update(
            """
            UPDATE outbox_events
               SET status = 'dispatched', dispatched_at = :dispatchedAt
             WHERE organization_id = :organizationId AND id = :id
            """.trimIndent(),
            MapSqlParameterSource()
                .addValue("organizationId", organizationId)
                .addValue("id", id)
                .addValue("dispatchedAt", Timestamp.from(now)),
        )
    }

    private fun markFailed(organizationId: String, id: String) {
        jdbc.update(
            """
            UPDATE outbox_events
               SET status = 'failed', attempts = attempts + 1
             WHERE organization_id = :organizationId AND id = :id
            """.trimIndent(),
            MapSqlParameterSource()
                .addValue("organizationId", organizationId)
                .addValue("id", id),
        )
    }
}
