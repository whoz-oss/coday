package io.whozoss.factory.workflow.service

import com.fasterxml.jackson.databind.ObjectMapper
import io.whozoss.factory.agentattempt.persistence.OutboxEventNode
import io.whozoss.factory.agentattempt.persistence.SpringDataNeo4jOutboxRepository
import io.whozoss.factory.persistence.TenantScope
import java.time.Instant
import java.nio.charset.StandardCharsets
import java.util.UUID
import org.springframework.stereotype.Service
import org.springframework.transaction.annotation.Transactional

/**
 * Durable submission of an asynchronous session run (Lot C / étape 3, HTTP /run).
 *
 * The HTTP entry point must answer fast (202) without holding the connection for
 * the whole agent turn. Rather than an in-memory `@Async` fire-and-forget (which
 * loses the run on restart), the submission is enqueued as a transactional
 * `:OutboxEvent` (`session_run_requested`) and drained by
 * [io.whozoss.factory.agentattempt.service.OutboxDrainWorker], which calls
 * [SessionRunService.runSession] after the enqueue transaction commits. The event
 * survives a restart, so the run is replayable: an undrained submission is picked
 * up by the next drain pass.
 */
@Service
class SessionRunSubmissionService(
    private val outbox: SpringDataNeo4jOutboxRepository,
    private val objectMapper: ObjectMapper,
) {

    data class SubmissionResult(
        val submissionId: String,
        val queued: Boolean,
        val idempotent: Boolean,
        val status: String,
    )

    /**
     * Durably enqueues a run/resume submission and returns its tracking id. No
     * agent turn is executed on this call — the bounded background worker drains
     * it.
     */
    @Transactional
    fun submit(
        scope: TenantScope,
        namespaceId: String,
        workflowId: String,
        repoRoot: String,
        operation: String,
        ticket: String?,
    ): SubmissionResult {
        val submissionId = stableSubmissionId(scope, namespaceId, workflowId, operation)
        val nodeId = OutboxEventNode.compositeId(scope.organizationId, submissionId)
        val existing = outbox.findActiveRunSubmission(nodeId, SESSION_RUN_REQUESTED)
        if (existing != null) {
            return SubmissionResult(
                submissionId = existing.eventId,
                queued = existing.status == PENDING,
                idempotent = true,
                status = existing.status,
            )
        }
        // A terminal failed submission may be explicitly re-submitted under a
        // new identity; active pending/dispatched rows remain deterministically
        // fenced by the stable id above.
        val eventId = if (outbox.existsById(nodeId)) UUID.randomUUID().toString() else submissionId
        val eventNodeId = OutboxEventNode.compositeId(scope.organizationId, eventId)
        val payload = buildMap<String, Any?> {
            put("submissionId", eventId)
            put("namespaceId", namespaceId)
            put("workflowId", workflowId)
            put("repoRoot", repoRoot)
            put("operation", operation)
            if (!ticket.isNullOrBlank()) put("ticket", ticket)
        }
        outbox.save(
            OutboxEventNode(
                id = eventNodeId,
                organizationId = scope.organizationId,
                eventId = eventId,
                workstreamId = scope.workstreamId,
                eventType = SESSION_RUN_REQUESTED,
                payload = objectMapper.writeValueAsString(payload),
                status = PENDING,
                attempts = 0,
                createdAt = Instant.now(),
                dispatchedAt = null,
            ),
        )
        return SubmissionResult(eventId, queued = true, idempotent = false, status = PENDING)
    }

    companion object {
        /** Outbox event type of a durable session-run submission. */
        const val SESSION_RUN_REQUESTED = "session_run_requested"

        const val PENDING = "pending"

        private fun stableSubmissionId(
            scope: TenantScope,
            namespaceId: String,
            workflowId: String,
            operation: String,
        ): String = UUID.nameUUIDFromBytes(
            "${scope.workstreamId}|$namespaceId|$workflowId|$operation".toByteArray(StandardCharsets.UTF_8),
        ).toString()
    }
}
