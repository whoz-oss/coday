package io.whozoss.factory.planchange.domain

/**
 * Deterministic taxonomy of a plan-change proposal (Phase 8 governed replanning).
 *
 * The kind is computed by [PlanChangeClassifier] from the submitted payload alone —
 * never from caller-supplied classification — so the same payload always yields the
 * same kind. The database/wire values are the stable uppercase taxonomy names.
 */
enum class PlanChangeKind(val dbValue: String) {
    /** Simple retry without any plan structural change. */
    RETRY_NO_PLAN_CHANGE("RETRY_NO_PLAN_CHANGE"),

    /** Select a pre-declared pathway/branch within the current workflow definition. */
    PATH_SELECTION("PATH_SELECTION"),

    /** Activate an optional pre-declared step of the current definition. */
    OPTIONAL_STEP_ACTIVATION("OPTIONAL_STEP_ACTIVATION"),

    /** Propose to add/remove a dependency between existing steps (DAG change). */
    DEPENDENCY_CHANGE_PROPOSAL("DEPENDENCY_CHANGE_PROPOSAL"),

    /** Propose a scope modification. */
    SCOPE_CHANGE_PROPOSAL("SCOPE_CHANGE_PROPOSAL"),

    /** Propose a new step. */
    NEW_STEP_PROPOSAL("NEW_STEP_PROPOSAL"),

    /** Propose a modification of a step contract, schema or oracle. */
    CONTRACT_OR_ORACLE_CHANGE_PROPOSAL("CONTRACT_OR_ORACLE_CHANGE_PROPOSAL"),
    ;

    companion object {
        /** Parse a persisted kind; an unknown stored value is data corruption. */
        fun fromDbValue(value: String): PlanChangeKind =
            entries.firstOrNull { it.dbValue == value.trim().uppercase() }
                ?: throw IllegalStateException("Unknown plan change kind: $value")
    }
}
