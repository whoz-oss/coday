package io.whozoss.factory.agentattempt.service

import io.whozoss.factory.agentattempt.persistence.OutboxEventNode
import io.whozoss.factory.agentattempt.persistence.SpringDataNeo4jOutboxRepository
import mu.KotlinLogging
import org.springframework.stereotype.Service
import org.springframework.transaction.annotation.Transactional
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
 * Neo4j polling drain service for the former V4 `outbox_events` table.
 *
 * The events are `:OutboxEvent` nodes enqueued by
 * [io.whozoss.factory.agentattempt.persistence.Neo4jAgentStepResultRepository] and
 * drained oldest-first. There is deliberately no `LISTEN`/`NOTIFY`.
 *
 * ## Mono-writer claim
 * The former SQL claim used `FOR UPDATE SKIP LOCKED` so two concurrent drain
 * passes never selected the same row. The embedded Neo4j engine is a
 * single-writer store, so no row lock can (or need) be taken: [selectPending]
 * simply reads the oldest pending events and [drainPending] flips each one
 * `dispatched` (handler returned) or `failed` (handler threw, incrementing
 * `attempts`) inside one transaction. A later pass can therefore retry or
 * dead-letter the failed events.
 */
@Service
class OutboxDrainService(
    private val outbox: SpringDataNeo4jOutboxRepository,
) {

    private val logger = KotlinLogging.logger {}

    /**
     * The distinct organizations that currently have at least one `pending`
     * event. A background drain worker iterates these instead of scanning every
     * tenant, so an idle deployment does no work.
     */
    @Transactional(readOnly = true)
    fun pendingOrganizations(): List<String> = outbox.findPendingOrganizations()

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
                outbox.markDispatched(organizationId, event.id, now)
                dispatched++
            } catch (failure: Exception) {
                logger.warn(failure) { "Outbox event ${event.id} failed to dispatch" }
                outbox.markFailed(organizationId, event.id)
                failed++
            }
        }
        return DrainReport(dispatched = dispatched, failed = failed)
    }

    private fun selectPending(organizationId: String, limit: Int): List<OutboxEvent> =
        outbox.findPending(organizationId, limit).map { node: OutboxEventNode ->
            OutboxEvent(
                id = node.eventId,
                eventType = node.eventType,
                payload = node.payload,
                attempts = node.attempts,
                workstreamId = node.workstreamId,
            )
        }
}
