package io.whozoss.factory.agentattempt.domain

import java.time.Instant

/**
 * Domain record of a durable agent execution attempt (Lot C durable-execution).
 *
 * The identity fields adhere strictly to the identifiers supplied by the bridge:
 * [attemptId], [caseId], [capabilityToken], [ownerToken], [turnCorrelation],
 * [commandId], [namespaceId], [workflowId], [stepId], [attemptNumber],
 * [agentName], [failureCode] and [lastObservedEventId].
 *
 * - [ownerToken] is the lease owner identity, a.k.a. the bridge `leaseToken`.
 *   The finalize path fences on it: a worker whose token diverged (lease lost,
 *   expired or preempted) is rejected.
 * - [commandId] is the bridge idempotency key of the dispatch command.
 * - [lastObservedEventId] is the authoritative scalar high-water mark of the
 *   execution event stream observed for this attempt. Extension point: if a
 *   bounded deduplication set is ever required, store it as a JSON list capped
 *   at a documented bound (e.g. 100 ids, drop-oldest) alongside this scalar.
 * - [leaseExpiresAt] is the deadline of the current claim. Once past it, another
 *   worker may re-claim the attempt, which rotates [ownerToken] and fences the
 *   previous owner out of finalization.
 */
data class DurableAgentAttempt(
    val attemptId: String,
    val caseId: String,
    val namespaceId: String,
    val workflowId: String,
    val stepId: String,
    val attemptNumber: Int,
    val agentName: String,
    val capabilityToken: String? = null,
    val ownerToken: String? = null,
    val turnCorrelation: String? = null,
    val commandId: String? = null,
    /**
     * The durable turn brief (the `@persona brief` message body) captured when
     * the attempt was first registered. Persisting the command payload on the
     * attempt is what lets the startup recovery worker re-drive a turn ONLY when
     * it is proven that `startTurn` was never accepted, without re-deriving the
     * brief from upstream evidence. Null on legacy records and on attempts the
     * bridge registered before the payload was persisted.
     */
    val brief: String? = null,
    val status: AgentAttemptStatus = AgentAttemptStatus.PENDING,
    val failureCode: String? = null,
    val resultEvidenceId: String? = null,
    val lastObservedEventId: String? = null,
    val revision: Int = 1,
    val createdAt: Instant = Instant.now(),
    val startedAt: Instant? = null,
    val updatedAt: Instant = Instant.now(),
    val completedAt: Instant? = null,
    val leaseExpiresAt: Instant? = null,
) {
    /** Whether the attempt may move from its current [status] to [next]. */
    fun canTransitionTo(next: AgentAttemptStatus): Boolean = status.canTransitionTo(next)
}
