package io.whozoss.factory.worker.domain

/**
 * Ordered lifecycle states a worker can take (V6 `workers.status` CHECK).
 *
 * Port of `WORKER_STATES` in `factory/src/domain/worker.ts`.
 */
enum class WorkerState(val dbValue: String) {
    OFFLINE("offline"),
    IDLE("idle"),
    BUSY("busy"),
    MAINTENANCE("maintenance");

    /** Whether the `this -> next` lifecycle transition is allowed by the state machine. */
    fun canTransitionTo(next: WorkerState): Boolean = next in ALLOWED_TRANSITIONS.getValue(this)

    companion object {
        private val BY_DB_VALUE: Map<String, WorkerState> = entries.associateBy { it.dbValue }

        /**
         * Allowed lifecycle transitions. A worker may always be drained to
         * `offline` (crash / shutdown) and taken into `maintenance`; `busy`
         * returns to `idle` when its work unit completes.
         */
        private val ALLOWED_TRANSITIONS: Map<WorkerState, Set<WorkerState>> = mapOf(
            OFFLINE to setOf(IDLE, MAINTENANCE),
            IDLE to setOf(BUSY, OFFLINE, MAINTENANCE),
            BUSY to setOf(IDLE, OFFLINE, MAINTENANCE),
            MAINTENANCE to setOf(OFFLINE, IDLE),
        )

        /** Parse a persisted state, case-insensitively. */
        fun fromDbValue(value: String): WorkerState =
            BY_DB_VALUE[value.lowercase()] ?: throw IllegalArgumentException("Unknown worker state: $value")

        /** Allowed lifecycle transitions keyed by source state. */
        fun transitions(): Map<WorkerState, Set<WorkerState>> = ALLOWED_TRANSITIONS
    }
}
