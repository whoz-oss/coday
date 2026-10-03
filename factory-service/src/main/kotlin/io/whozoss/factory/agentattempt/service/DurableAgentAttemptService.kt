package io.whozoss.factory.agentattempt.service

import io.whozoss.factory.agentattempt.domain.AgentAttemptStatus
import io.whozoss.factory.agentattempt.domain.DurableAgentAttempt
import io.whozoss.factory.agentattempt.domain.DurableAgentAttemptJournalEntry
import io.whozoss.factory.agentattempt.persistence.DurableAgentAttemptRepository
import io.whozoss.factory.agentattempt.persistence.ScopedDurableAgentAttempt
import io.whozoss.factory.persistence.TenantScope
import org.springframework.stereotype.Service
import org.springframework.transaction.annotation.Transactional
import java.time.Instant

/**
 * Transactional application service of the durable execution attempt lifecycle
 * (Lot C durable-execution).
 *
 * Exposes the atomic claim, the owner-guarded intermediate transitions and the
 * lease-fenced finalization on top of [DurableAgentAttemptRepository]. The
 * intended caller is the SSE/execution adapter (built in parallel, out of scope
 * here); this service is deliberately **not** wired into `SessionRunService`.
 */
@Service
class DurableAgentAttemptService(
    private val repository: DurableAgentAttemptRepository,
) {

    /**
     * Idempotently register a bridge-supplied attempt. A re-submission with the
     * same `attemptId` returns the existing record without altering its state.
     */
    @Transactional
    fun register(
        scope: TenantScope,
        attempt: DurableAgentAttempt,
        now: Instant = Instant.now(),
    ): DurableAgentAttempt = repository.register(scope, attempt, now)

    /**
     * Atomically claim the attempt for [ownerToken]. Exactly one concurrent
     * claim wins; the losers are rejected with `ATTEMPT_CLAIM_CONFLICT`. A
     * repeated claim by the same owner is idempotent.
     */
    @Transactional
    fun claim(
        scope: TenantScope,
        namespaceId: String,
        workflowId: String,
        stepId: String,
        attemptId: String,
        ownerToken: String,
        leaseTtlMs: Long? = null,
        now: Instant = Instant.now(),
    ): DurableAgentAttempt = repository.claim(
        scope,
        namespaceId,
        workflowId,
        stepId,
        attemptId,
        ownerToken,
        leaseExpiresAt = leaseTtlMs?.let { now.plusMillis(it) },
        now = now,
    )

    /** Owner-guarded intermediate transition (`starting`, `running`, `waiting_human`). */
    @Transactional
    fun transition(
        scope: TenantScope,
        namespaceId: String,
        workflowId: String,
        stepId: String,
        attemptId: String,
        ownerToken: String,
        target: AgentAttemptStatus,
        lastObservedEventId: String? = null,
        now: Instant = Instant.now(),
    ): DurableAgentAttempt = repository.transition(
        scope,
        namespaceId,
        workflowId,
        stepId,
        attemptId,
        ownerToken,
        target,
        lastObservedEventId,
        now,
    )

    /**
     * Finalize the attempt to a terminal [target], fenced on [ownerToken]: a
     * worker whose lease token diverged (expired, revoked or preempted) is
     * rejected with `ATTEMPT_LEASE_FENCED`. `succeeded` is reachable only from
     * `running` / `waiting_human` — a timeout, incomplete or unknown outcome
     * must be finalized as `indeterminate` (or `failed` / `interrupted`).
     */
    @Transactional
    fun finalize(
        scope: TenantScope,
        namespaceId: String,
        workflowId: String,
        stepId: String,
        attemptId: String,
        ownerToken: String,
        target: AgentAttemptStatus,
        failureCode: String? = null,
        resultEvidenceId: String? = null,
        lastObservedEventId: String? = null,
        now: Instant = Instant.now(),
    ): DurableAgentAttempt = repository.finalize(
        scope,
        namespaceId,
        workflowId,
        stepId,
        attemptId,
        ownerToken,
        target,
        failureCode,
        resultEvidenceId,
        lastObservedEventId,
        now,
    )

    @Transactional(readOnly = true)
    fun find(
        scope: TenantScope,
        namespaceId: String,
        workflowId: String,
        stepId: String,
        attemptId: String,
    ): DurableAgentAttempt? = repository.find(scope, namespaceId, workflowId, stepId, attemptId)

    /**
     * Locate an attempt by its bridge `attemptId` within a workflow (any step).
     * Used by the explicit cancellation route.
     */
    @Transactional(readOnly = true)
    fun findByAttemptId(
        scope: TenantScope,
        namespaceId: String,
        workflowId: String,
        attemptId: String,
    ): DurableAgentAttempt? = repository.findByAttemptId(scope, namespaceId, workflowId, attemptId)

    /**
     * Every attempt of a workflow, across all its steps. Used by the workflow
     * real-cost aggregation to resolve the case ids the workflow produced.
     */
    @Transactional(readOnly = true)
    fun findByWorkflow(
        scope: TenantScope,
        namespaceId: String,
        workflowId: String,
    ): List<DurableAgentAttempt> = repository.findByWorkflow(scope, namespaceId, workflowId)

    /**
     * The append-only transition journal of the attempt, oldest entry first.
     * Every landed state change (registration, claim, transition, finalization,
     * cancellation) is recorded with its monotone sequence; fenced/conflicted
     * mutations and idempotent replays leave no trace.
     */
    @Transactional(readOnly = true)
    fun journal(
        scope: TenantScope,
        namespaceId: String,
        workflowId: String,
        stepId: String,
        attemptId: String,
    ): List<DurableAgentAttemptJournalEntry> = repository.journal(scope, namespaceId, workflowId, stepId, attemptId)

    /**
     * Every non-terminal attempt of the whole graph, across all tenant scopes and
     * namespaces. The startup recovery worker sweeps this list to reconcile or
     * resume attempts orphaned by a crash.
     */
    @Transactional(readOnly = true)
    fun findNonTerminal(limit: Int = DEFAULT_RECOVERY_LIMIT): List<ScopedDurableAgentAttempt> =
        repository.findNonTerminal(limit)

    /**
     * Explicit business cancellation fenced on [expectedRevision]: the attempt is
     * moved to `interrupted` and its lease owner token rotated. Idempotent when
     * already interrupted; a divergent revision is a conflict; any other terminal
     * status is an invalid transition.
     */
    @Transactional
    fun requestCancel(
        scope: TenantScope,
        namespaceId: String,
        workflowId: String,
        stepId: String,
        attemptId: String,
        expectedRevision: Int? = null,
        failureCode: String? = "USER_CANCELLED",
        now: Instant = Instant.now(),
    ): DurableAgentAttempt = repository.cancel(
        scope,
        namespaceId,
        workflowId,
        stepId,
        attemptId,
        expectedRevision,
        failureCode,
        now,
    )

    companion object {
        /** Default bound of the startup recovery sweep. */
        const val DEFAULT_RECOVERY_LIMIT = 100
    }
}
