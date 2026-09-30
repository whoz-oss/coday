package io.whozoss.factory.agentattempt.domain

/**
 * Machine-readable error codes of the durable execution attempt aggregate
 * (Lot C durable-execution).
 *
 * Lives in its own object so the result-path vocabulary of
 * [AgentAttemptErrorCodes] is left untouched. Tests assert on these codes,
 * never on messages.
 */
object DurableAgentAttemptErrorCodes {
    /** 409 — another execution already owns the attempt/step; the claim lost the race. */
    const val ATTEMPT_CLAIM_CONFLICT = "ATTEMPT_CLAIM_CONFLICT"

    /**
     * 409 — the caller's `ownerToken`/`leaseToken` diverged or its lease expired:
     * finalization is fenced out.
     */
    const val ATTEMPT_LEASE_FENCED = "ATTEMPT_LEASE_FENCED"

    /** 409 — the requested status is not reachable from the current one. */
    const val ATTEMPT_INVALID_TRANSITION = "ATTEMPT_INVALID_TRANSITION"

    /** 404 — no durable attempt exists for the given identity in the caller's scope. */
    const val ATTEMPT_NOT_FOUND = "ATTEMPT_NOT_FOUND"
}

/**
 * 409 — the atomic claim lost the race: another execution already owns this
 * attempt (or the step) at this instant.
 */
class AttemptClaimConflictException(
    message: String = "The attempt is already claimed by another execution",
    details: Any? = null,
) : AgentAttemptException(DurableAgentAttemptErrorCodes.ATTEMPT_CLAIM_CONFLICT, 409, message, details)

/**
 * 409 — lease fencing rejection at finalization: the caller's
 * `ownerToken`/`leaseToken` is no longer the owner of the attempt (diverged,
 * expired or preempted by a re-claim), so it must not finalize.
 */
class AttemptLeaseFencingException(
    message: String = "The lease token is no longer the owner of this attempt",
    details: Any? = null,
) : AgentAttemptException(DurableAgentAttemptErrorCodes.ATTEMPT_LEASE_FENCED, 409, message, details)

/**
 * 409 — the requested status transition is not allowed by
 * [AgentAttemptStatus]. In particular, an incomplete, timed-out or unknown
 * outcome can never be finalized as `succeeded`.
 */
class InvalidAttemptTransitionException(
    message: String = "The requested attempt status transition is not allowed",
    details: Any? = null,
) : AgentAttemptException(DurableAgentAttemptErrorCodes.ATTEMPT_INVALID_TRANSITION, 409, message, details)

/** 404 — the durable attempt does not exist in the caller's scope. */
class AttemptNotFoundException(
    message: String = "Durable agent attempt not found",
    details: Any? = null,
) : AgentAttemptException(DurableAgentAttemptErrorCodes.ATTEMPT_NOT_FOUND, 404, message, details)
