package io.whozoss.factory.agentattempt.domain

/**
 * Lifecycle states of a durable agent execution attempt (Lot C durable-execution).
 *
 * Mirrors the shape of [io.whozoss.factory.workunit.domain.WorkUnitState]: a
 * persisted [dbValue], a [terminal] flag, a guarded [canTransitionTo] and an
 * `ALLOWED_TRANSITIONS` map.
 *
 * ## Safety invariant
 * [SUCCEEDED] is reachable **only** from [RUNNING] or [WAITING_HUMAN]. A
 * timeout, a lost lease or an unknown/incomplete outcome must be expressed as
 * [INDETERMINATE] (or [FAILED] / [INTERRUPTED]) — never as [SUCCEEDED].
 *
 * ## Superseding (Phase 4 ask-step-question)
 * [SUPERSEDED] is the terminal record of an attempt that asked a human
 * question (`waiting_human`) and whose answer was received: the step resumes
 * as a brand-new attempt `N+1` carrying the bounded resumption context, while
 * attempt `N` stays an immutable `superseded` record. It is NEVER reactivated
 * or rewritten, and it is not a success.
 */
enum class AgentAttemptStatus(val dbValue: String) {
    PENDING("pending"),
    CLAIMING("claiming"),
    STARTING("starting"),
    RUNNING("running"),
    WAITING_HUMAN("waiting_human"),
    SUCCEEDED("succeeded"),
    FAILED("failed"),
    INDETERMINATE("indeterminate"),
    INTERRUPTED("interrupted"),
    SUPERSEDED("superseded"),
    ;

    /** Terminal states: no transition leaves them. */
    val terminal: Boolean
        get() = this == SUCCEEDED || this == FAILED || this == INDETERMINATE || this == INTERRUPTED || this == SUPERSEDED

    /** True only for [SUCCEEDED]; an incomplete or unknown outcome is never a success. */
    val isSuccess: Boolean
        get() = this == SUCCEEDED

    /** Whether the `this -> next` lifecycle transition is allowed by the state machine. */
    fun canTransitionTo(next: AgentAttemptStatus): Boolean = next in ALLOWED_TRANSITIONS.getValue(this)

    companion object {
        private val BY_DB_VALUE: Map<String, AgentAttemptStatus> = entries.associateBy { it.dbValue }

        /**
         * Allowed lifecycle transitions. The key invariant is that [SUCCEEDED]
         * appears only in the target sets of [RUNNING] and [WAITING_HUMAN], so a
         * pending/claiming/starting attempt (e.g. one that timed out before the
         * agent ever ran) can never flip straight to a success.
         */
        private val ALLOWED_TRANSITIONS: Map<AgentAttemptStatus, Set<AgentAttemptStatus>> = mapOf(
            PENDING to setOf(CLAIMING, INTERRUPTED),
            CLAIMING to setOf(STARTING, FAILED, INDETERMINATE, INTERRUPTED),
            STARTING to setOf(RUNNING, FAILED, INDETERMINATE, INTERRUPTED),
            RUNNING to setOf(WAITING_HUMAN, SUCCEEDED, FAILED, INDETERMINATE, INTERRUPTED),
            WAITING_HUMAN to setOf(RUNNING, SUCCEEDED, FAILED, INDETERMINATE, INTERRUPTED, SUPERSEDED),
            SUCCEEDED to emptySet(),
            FAILED to emptySet(),
            INDETERMINATE to emptySet(),
            INTERRUPTED to emptySet(),
            SUPERSEDED to emptySet(),
        )

        /** Parse a persisted state, case-insensitively. */
        fun fromDbValue(value: String): AgentAttemptStatus =
            BY_DB_VALUE[value.lowercase()] ?: throw IllegalArgumentException("Unknown agent attempt status: $value")

        /** Allowed lifecycle transitions keyed by source state. */
        fun transitions(): Map<AgentAttemptStatus, Set<AgentAttemptStatus>> = ALLOWED_TRANSITIONS
    }
}
