package io.whozoss.factory.workunit.domain

/**
 * Ordered lifecycle states a work unit can take (V6 `work_units.status` CHECK).
 *
 * Port of `WORK_UNIT_STATES` in `factory/src/domain/work-unit.ts`.
 */
enum class WorkUnitState(val dbValue: String) {
    CREATED("created"),
    ASSIGNED("assigned"),
    RUNNING("running"),
    COMPLETED("completed"),
    FAILED("failed"),
    CANCELLED("cancelled");

    /** Terminal states: no transition leaves them. */
    val terminal: Boolean
        get() = this == COMPLETED || this == FAILED || this == CANCELLED

    /** Whether the `this -> next` lifecycle transition is allowed by the state machine. */
    fun canTransitionTo(next: WorkUnitState): Boolean = next in ALLOWED_TRANSITIONS.getValue(this)

    companion object {
        private val BY_DB_VALUE: Map<String, WorkUnitState> = entries.associateBy { it.dbValue }

        /**
         * Allowed lifecycle transitions. `failed` is terminal: a retry is a new
         * work unit, `attempt_count` only records how many attempts were made.
         */
        private val ALLOWED_TRANSITIONS: Map<WorkUnitState, Set<WorkUnitState>> = mapOf(
            CREATED to setOf(ASSIGNED, CANCELLED),
            ASSIGNED to setOf(RUNNING, CREATED, CANCELLED, FAILED),
            RUNNING to setOf(COMPLETED, FAILED, CANCELLED),
            COMPLETED to emptySet(),
            FAILED to emptySet(),
            CANCELLED to emptySet(),
        )

        /** Parse a persisted state, case-insensitively. */
        fun fromDbValue(value: String): WorkUnitState =
            BY_DB_VALUE[value.lowercase()] ?: throw IllegalArgumentException("Unknown work unit state: $value")

        /** Allowed lifecycle transitions keyed by source state. */
        fun transitions(): Map<WorkUnitState, Set<WorkUnitState>> = ALLOWED_TRANSITIONS
    }
}
