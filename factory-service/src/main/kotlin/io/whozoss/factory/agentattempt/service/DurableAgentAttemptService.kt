package io.whozoss.factory.agentattempt.service

import io.whozoss.factory.agentattempt.domain.AgentAttemptStatus
import io.whozoss.factory.agentattempt.domain.DurableAgentAttempt
import io.whozoss.factory.agentattempt.persistence.DurableAgentAttemptRepository
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
}
