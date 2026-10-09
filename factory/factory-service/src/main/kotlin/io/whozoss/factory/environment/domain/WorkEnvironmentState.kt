package io.whozoss.factory.environment.domain

import com.fasterxml.jackson.annotation.JsonCreator
import com.fasterxml.jackson.annotation.JsonValue

/**
 * Ordered lifecycle states a work environment can take.
 *
 * The database values are exactly the V6 `work_environments` CHECK vocabulary:
 * `provisioning`, `ready`, `busy`, `decommissioned`. `provisioning` -> `ready`
 * is the happy provisioning path; `ready` <-> `busy` tracks whether the
 * environment is currently bound to an active work unit; `decommissioned` is
 * terminal.
 *
 * The JSON representation is the lowercase database vocabulary (via [JsonValue])
 * so the wire format, the JSONB payload and the `status` column never diverge.
 */
enum class WorkEnvironmentState(@get:JsonValue val dbValue: String) {
    PROVISIONING("provisioning"),
    READY("ready"),
    BUSY("busy"),
    DECOMMISSIONED("decommissioned");

    /** Terminal states: no transition leaves them. */
    val terminal: Boolean
        get() = this == DECOMMISSIONED

    /** Whether the `this -> next` lifecycle transition is allowed by the state machine. */
    fun canTransitionTo(next: WorkEnvironmentState): Boolean = next in ALLOWED_TRANSITIONS.getValue(this)

    companion object {
        private val BY_DB_VALUE: Map<String, WorkEnvironmentState> = entries.associateBy { it.dbValue }

        private val ALLOWED_TRANSITIONS: Map<WorkEnvironmentState, Set<WorkEnvironmentState>> = mapOf(
            PROVISIONING to setOf(READY, DECOMMISSIONED),
            READY to setOf(BUSY, DECOMMISSIONED),
            BUSY to setOf(READY, DECOMMISSIONED),
            DECOMMISSIONED to emptySet(),
        )

        /** Parse a persisted state, case-insensitively. */
        fun fromDbValue(value: String): WorkEnvironmentState =
            BY_DB_VALUE[value.lowercase()] ?: throw IllegalArgumentException("Unknown environment state: $value")

        /** Jackson deserialization entry point (lowercase database vocabulary). */
        @JvmStatic
        @JsonCreator
        fun fromJson(value: String): WorkEnvironmentState = fromDbValue(value)

        /** Allowed lifecycle transitions keyed by source state. */
        fun transitions(): Map<WorkEnvironmentState, Set<WorkEnvironmentState>> = ALLOWED_TRANSITIONS
    }
}
