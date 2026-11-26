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
    val retryDeadline: Instant? = null,
    val claimToken: String? = null,
)

/** Outcome of one drain pass. */
data class DrainReport(
    val dispatched: Int,
    val failed: Int,
)

/** Status and due time of the continuation of a workflow, as stored. */
data class ContinuationSnapshot(val status: String, val nextAttemptAt: Instant?)

/**
 * Neo4j polling drain service for the former V4 `outbox_events` table.
 *
 * The events are `:OutboxEvent` nodes enqueued by
 * [io.whozoss.factory.agentattempt.persistence.Neo4jAgentStepResultRepository] and
 * drained oldest-first. There is deliberately no `LISTEN`/`NOTIFY`.
 *
 * Source handoff stays inside [drainPending]'s short transaction. Continuations
 * use a graph write-lock/CAS claim with a lease; acknowledgement and deferral
 * require the current token. Expired claims are selectable after restart.
 * No external provisioning probe or agent turn holds this transaction open.
 */
@Service
class OutboxDrainService(
    private val outbox: SpringDataNeo4jOutboxRepository,
    private val objectMapper: com.fasterxml.jackson.databind.ObjectMapper = com.fasterxml.jackson.databind.ObjectMapper(),
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
     * `failed`, returning normally marks it `dispatched`. Failed rows remain
     * selectable for bounded retry by the scheduled worker.
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

    /**
     * Called inside drainPending's short transaction: handoff and acknowledgement commit together.
     * A new source ID is a new wake; re-delivery of the current ID is not a new retry budget.
     * Collapsed wakes are harmless: the sequencer observes all authoritative durable DAG states.
     */
    @Transactional(propagation = org.springframework.transaction.annotation.Propagation.MANDATORY)
    fun enqueueContinuation(organizationId: String, source: OutboxEvent, payload: String, now: Instant = Instant.now()) {
        val coordinates = objectMapper.readTree(payload)
        enqueue(io.whozoss.factory.persistence.TenantScope(organizationId, source.workstreamId),
            coordinates.path("namespaceId").asText(), coordinates.path("workflowId").asText(), payload, now, false, source.id)
    }

    /** Common runSession boundary: caller identity stays on the workflow, never supplied by the wake-up. */
    @Transactional(propagation = org.springframework.transaction.annotation.Propagation.REQUIRES_NEW)
    fun adoptionDeferred(scope: io.whozoss.factory.persistence.TenantScope, namespaceId: String, workflowId: String,
        repoRoot: java.nio.file.Path, ticket: String?) {
        val payload = objectMapper.writeValueAsString(mapOf("namespaceId" to namespaceId, "workflowId" to workflowId,
            "repoRoot" to repoRoot.toString(), "ticket" to ticket))
        enqueue(scope, namespaceId, workflowId, payload, Instant.now(), true)
    }

    /**
     * Parks the continuation of a run whose step waits for an external condition: it becomes due again at
     * [notBefore] (external polling), without holding a worker slot in between. Idempotent: the earliest
     * requested instant wins, an immediate wake (result submission) always wins over a delayed one, and an
     * adoption backoff is never touched. Runs in its own short transaction so it also works when the
     * sequencer is called outside any transaction.
     */
    @Transactional(propagation = org.springframework.transaction.annotation.Propagation.REQUIRES_NEW)
    fun scheduleWake(scope: io.whozoss.factory.persistence.TenantScope, namespaceId: String, workflowId: String,
        repoRoot: java.nio.file.Path, ticket: String?, notBefore: Instant) {
        requestWake(scope, namespaceId, workflowId, wakePayload(namespaceId, workflowId, repoRoot, ticket), notBefore)
    }

    /**
     * Same delayed wake, but joined to the CALLER's transaction (propagation MANDATORY): the sequencer parks a
     * step and requests its wake atomically, so a step is `waiting_external` if and only if a delayed wake exists.
     * A failure here rolls the park back with it.
     */
    @Transactional(propagation = org.springframework.transaction.annotation.Propagation.MANDATORY)
    fun scheduleWakeInCurrentTransaction(scope: io.whozoss.factory.persistence.TenantScope, namespaceId: String,
        workflowId: String, repoRoot: java.nio.file.Path, ticket: String?, notBefore: Instant) {
        requestWake(scope, namespaceId, workflowId, wakePayload(namespaceId, workflowId, repoRoot, ticket), notBefore)
    }

    /**
     * Safety-net re-arm used by the sweeper of parked steps: the repo root and ticket come from the last
     * `session_run_requested` submission of the workflow; without one the worker resolves the namespace repo root
     * itself (`agentos-namespace`), exactly as for a result wake.
     */
    @Transactional(propagation = org.springframework.transaction.annotation.Propagation.REQUIRES_NEW)
    fun rearmExternalWait(scope: io.whozoss.factory.persistence.TenantScope, namespaceId: String, workflowId: String,
        notBefore: Instant) {
        val payload = submissionContext(scope.organizationId, scope.workstreamId, namespaceId, workflowId)
            ?: objectMapper.writeValueAsString(mapOf("namespaceId" to namespaceId, "workflowId" to workflowId,
                "repoRootSource" to "agentos-namespace"))
        requestWake(scope, namespaceId, workflowId, payload, notBefore)
    }

    /** Read-only view of the continuation of a workflow, or null when none exists. */
    @Transactional(readOnly = true)
    fun continuationSnapshot(scope: io.whozoss.factory.persistence.TenantScope, namespaceId: String,
        workflowId: String): ContinuationSnapshot? =
        outbox.findById(OutboxEventNode.compositeId(scope.organizationId, continuationId(scope.workstreamId, namespaceId, workflowId)))
            .orElse(null)?.let { ContinuationSnapshot(it.status, it.nextAttemptAt) }

    private fun wakePayload(namespaceId: String, workflowId: String, repoRoot: java.nio.file.Path, ticket: String?): String =
        objectMapper.writeValueAsString(mapOf("namespaceId" to namespaceId, "workflowId" to workflowId,
            "repoRoot" to repoRoot.toString(), "ticket" to ticket))

    private fun requestWake(scope: io.whozoss.factory.persistence.TenantScope, namespaceId: String, workflowId: String,
        payload: String, notBefore: Instant) {
        val eventId = continuationId(scope.workstreamId, namespaceId, workflowId)
        outbox.requestDelayedWake(OutboxEventNode.compositeId(scope.organizationId, eventId), scope.organizationId,
            eventId, scope.workstreamId, payload, Instant.now(), notBefore)
    }

    private fun enqueue(scope: io.whozoss.factory.persistence.TenantScope, namespaceId: String, workflowId: String,
        payload: String, now: Instant, deferred: Boolean, sourceId: String? = null) {
        val eventId = continuationId(scope.workstreamId, namespaceId, workflowId)
        outbox.enqueueContinuation(OutboxEventNode.compositeId(scope.organizationId, eventId), scope.organizationId,
            eventId, scope.workstreamId, payload, now, if (deferred) now.plusSeconds(RETRY_SECONDS) else null,
            if (deferred) now.plusSeconds(5) else null, !deferred, sourceId)
        if (deferred) outbox.beginAdoptionWait(OutboxEventNode.compositeId(scope.organizationId, eventId),
            now.plusSeconds(RETRY_SECONDS), now.plusSeconds(5))
    }

    /** Database-only lookup; result handoff must never perform HTTP inside its transaction. */
    @Transactional(readOnly = true)
    fun submissionContext(organizationId: String, workstreamId: String, namespaceId: String, workflowId: String): String? =
        outbox.findSubmissionContexts(organizationId, workstreamId).firstNotNullOfOrNull {
            val json = objectMapper.readTree(it.payload)
            it.payload.takeIf { json.path("namespaceId").asText() == namespaceId &&
                json.path("workflowId").asText() == workflowId && json.path("repoRoot").isTextual &&
                json.path("repoRoot").asText().isNotBlank() }
        }

    @Transactional
    fun releaseContinuation(organizationId: String, eventId: String, token: String) {
        outbox.releaseContinuation(OutboxEventNode.compositeId(organizationId, eventId), token)
    }

    @Transactional(readOnly = true)
    fun dueContinuations(organizationId: String, now: Instant, limit: Int = 50): List<OutboxEvent> =
        outbox.findDueContinuations(organizationId, now, limit).map {
            OutboxEvent(it.eventId, it.eventType, it.payload, it.attempts, it.workstreamId, it.retryDeadline, it.claimToken)
        }

    @Transactional
    fun claimContinuation(organizationId: String, eventId: String, now: Instant): OutboxEvent? {
        val node = outbox.claimContinuation(OutboxEventNode.compositeId(organizationId, eventId),
            java.util.UUID.randomUUID().toString(), now, now.plusSeconds(LEASE_SECONDS)) ?: return null
        return OutboxEvent(node.eventId, node.eventType, node.payload, node.attempts, node.workstreamId, node.retryDeadline, node.claimToken)
    }

    @Transactional
    fun completeContinuation(organizationId: String, eventId: String, now: Instant, token: String) {
        outbox.acknowledgeContinuation(OutboxEventNode.compositeId(organizationId, eventId), token, now)
    }

    /** Only the adoption transient path is deferred. Other failures are dead letters, never agent retries. */
    @Transactional
    fun deferContinuation(organizationId: String, eventId: String, now: Instant, transient: Boolean, token: String) {
        val id = OutboxEventNode.compositeId(organizationId, eventId)
        val current = outbox.findById(id).orElseThrow()
        val delaySeconds = minOf(300L, 5L shl minOf(current.attempts, 6))
        outbox.deferClaimedContinuation(id, token, now, now.plusSeconds(delaySeconds), now.plusSeconds(RETRY_SECONDS), transient)
    }

    companion object {
        const val RETRY_SECONDS = 90L * 60
        // Full sequencer turns run on the bounded run pool of OutboxDrainWorker. After an ungraceful crash,
        // recovery may wait up to two hours; intentional interruption releases the claim.
        // No heartbeat/lease-renewal framework or automatic agent retry is introduced.
        const val LEASE_SECONDS = 2L * 60 * 60
        fun continuationId(workstreamId: String, namespaceId: String, workflowId: String): String =
            "continuation:" + java.util.UUID.nameUUIDFromBytes(
                "$workstreamId|$namespaceId|$workflowId".toByteArray(Charsets.UTF_8))
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
