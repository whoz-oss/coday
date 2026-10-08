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
    /**
     * The `environmentId` of the work environment this attempt was bound to at
     * reservation time, and the environment optimistic-lock revision it
     * captured then. Both are set once at registration and never mutated
     * afterwards; `null` on legacy records and on attempts reserved before any
     * environment existed for the workflow.
     */
    val environmentRef: String? = null,
    val expectedEnvironmentRevision: Int? = null,
    /**
     * The bounded resumption context of a successor attempt `N+1` (Phase 4
     * ask-step-question): a compact JSON document holding the question, the
     * audited human answer and actor, and the predecessor
     * `attemptId`/`interactionId` links. Set ONCE at registration of the
     * successor attempt, never mutated afterwards; `null` on first attempts and
     * on attempts registered before the ask-step-question channel existed.
     * Bounded to
     * [StepQuestionLimits.RESUMPTION_CONTEXT_BYTES] UTF-8 bytes.
     */
    val resumptionContext: String? = null,
    /**
     * The frozen context envelope (Lot D) of this attempt, serialized as JSON
     * once at registration (see [AttemptContextEnvelope]). A replay reuses it
     * verbatim instead of re-deriving the context from upstream evidence, so a
     * replayed attempt observes exactly the context it was first registered
     * with. `null` on legacy records and on attempts registered before the
     * frozen envelope existed.
     */
    val contextEnvelope: String? = null,
    /**
     * The amendment sequence this attempt's context was frozen against
     * (Lot D schema field). Carried for replay/completeness only; the
     * amendment resolution logic belongs to Lot E and is intentionally absent.
     * `null` when no amendment pin applies.
     */
    val expectedAmendmentSeq: Long? = null,
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
    /**
     * Root case id of the durable case family this attempt belongs to (Lot B
     * durable case family). It identifies the run's worktree and is shared by
     * every attempt of the run. Set once at registration and never mutated;
     * `null` on legacy records and on runs that predate the case family.
     */
    val rootCaseId: String? = null,
    /**
     * Parent case of this attempt: `null` for the entry (root) attempt and the
     * [rootCaseId] for every subsequent (child) attempt of the family. Set once
     * at registration and never mutated; `null` on legacy records.
     */
    val parentCaseId: String? = null,
) {
    /** Whether the attempt may move from its current [status] to [next]. */
    fun canTransitionTo(next: AgentAttemptStatus): Boolean = status.canTransitionTo(next)
}
