package io.whozoss.factory.agentattempt.persistence

import io.whozoss.factory.agentattempt.domain.AgentAttemptStatus
import io.whozoss.factory.agentattempt.domain.DurableAgentAttempt
import io.whozoss.factory.persistence.TenantScope
import java.time.Instant

/**
 * Persistence port of the durable execution attempt aggregate
 * (Lot C durable-execution).
 *
 * The attempt is the mutable root of the durable-execution lifecycle, keyed by
 * the bridge-supplied identity
 * `(organizationId, workstreamId, namespaceId, workflowId, stepId, attemptId)`.
 * Idempotence is by `attemptId`: a re-submission reuses the existing attempt
 * and never creates a duplicate.
 */
interface DurableAgentAttemptRepository {

    /**
     * Idempotently register the attempt. A re-registration for the same
     * `attemptId` returns the pre-existing record without altering its state.
     */
    fun register(scope: TenantScope, attempt: DurableAgentAttempt, now: Instant): DurableAgentAttempt

    /** Read the attempt, or `null` when absent from the caller's scope. */
    fun find(
        scope: TenantScope,
        namespaceId: String,
        workflowId: String,
        stepId: String,
        attemptId: String,
    ): DurableAgentAttempt?

    /**
     * Atomically claim the attempt for [ownerToken], moving it to `claiming`
     * and setting the lease deadline. Exactly one concurrent claim wins; every
     * other is rejected with
     * [io.whozoss.factory.agentattempt.domain.AttemptClaimConflictException].
     * A repeated claim by the same owner is idempotent.
     */
    fun claim(
        scope: TenantScope,
        namespaceId: String,
        workflowId: String,
        stepId: String,
        attemptId: String,
        ownerToken: String,
        leaseExpiresAt: Instant?,
        now: Instant,
    ): DurableAgentAttempt

    /**
     * Owner-guarded intermediate (non-terminal) transition. The state machine
     * is validated in-domain first, then the update is fenced on [ownerToken].
     */
    fun transition(
        scope: TenantScope,
        namespaceId: String,
        workflowId: String,
        stepId: String,
        attemptId: String,
        ownerToken: String,
        target: AgentAttemptStatus,
        lastObservedEventId: String?,
        now: Instant,
    ): DurableAgentAttempt

    /**
     * Finalize the attempt to a terminal [target], fenced on [ownerToken]: a
     * worker whose lease token diverged (expired, revoked or preempted) is
     * rejected with
     * [io.whozoss.factory.agentattempt.domain.AttemptLeaseFencingException].
     * A replay to the same terminal status by the same owner is idempotent.
     */
    fun finalize(
        scope: TenantScope,
        namespaceId: String,
        workflowId: String,
        stepId: String,
        attemptId: String,
        ownerToken: String,
        target: AgentAttemptStatus,
        failureCode: String?,
        resultEvidenceId: String?,
        lastObservedEventId: String?,
        now: Instant,
    ): DurableAgentAttempt
}
