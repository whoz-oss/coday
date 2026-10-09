package io.whozoss.factory.planchange.domain

/**
 * Submitter-declared discriminator of a plan-change proposal.
 *
 * The declared type makes classification auditable; the deterministic
 * [PlanChangeClassifier] combines it with the payload shape (a structural payload
 * can never be downgraded by a benign declared type). The wire values are the
 * stable uppercase names.
 */
enum class PlanChangeProposalType(val wireValue: String) {
    /** Simple retry of an existing step, no plan change. */
    RETRY("RETRY"),

    /** Select a pre-declared pathway/branch of the current definition. */
    PATH_SELECTION("PATH_SELECTION"),

    /** Activate an optional pre-declared step of the current definition. */
    OPTIONAL_STEP("OPTIONAL_STEP"),

    /** Add/remove a dependency between existing steps. */
    DEPENDENCY("DEPENDENCY"),

    /** Modify the scope. */
    SCOPE("SCOPE"),

    /** Introduce a new step. */
    NEW_STEP("NEW_STEP"),

    /** Modify a step contract, schema or oracle. */
    CONTRACT_OR_ORACLE("CONTRACT_OR_ORACLE"),
    ;

    companion object {
        /** Parse a caller-supplied type, case-insensitively; `null` when unknown. */
        fun parse(value: String?): PlanChangeProposalType? =
            value?.trim()?.uppercase()?.let { normalized -> entries.firstOrNull { it.wireValue == normalized } }
    }
}
