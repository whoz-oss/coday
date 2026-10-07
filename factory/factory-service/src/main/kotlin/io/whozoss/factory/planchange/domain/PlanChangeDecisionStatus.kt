package io.whozoss.factory.planchange.domain

/**
 * Lifecycle status of a plan-change proposal decision.
 *
 * A proposal is created as [PENDING_VALIDATION]; the immutable decision log then
 * records one of the four terminal/managed outcomes. The wire values are the
 * stable uppercase names the `/decide` endpoint accepts.
 */
enum class PlanChangeDecisionStatus(val dbValue: String) {
    /** Submitted, classified, awaiting a decision. */
    PENDING_VALIDATION("PENDING_VALIDATION"),

    /** Pre-declared variation self-applied within the current definition (Rule 1). */
    AUTO_APPLIED("AUTO_APPLIED"),

    /** A human governance gate is required before any application (Rules 2–3). */
    GATE_REQUIRED("GATE_REQUIRED"),

    /** The change requires a new workflow definition / successor projection (Rule 2). */
    REQUIRES_NEW_DEFINITION("REQUIRES_NEW_DEFINITION"),

    /** The proposal was rejected. */
    REJECTED("REJECTED"),
    ;

    companion object {
        /** The four outcomes a `/decide` call may record (never [PENDING_VALIDATION]). */
        val DECIDABLE: Set<PlanChangeDecisionStatus> =
            setOf(AUTO_APPLIED, GATE_REQUIRED, REQUIRES_NEW_DEFINITION, REJECTED)

        /** Parse a persisted status; an unknown stored value is data corruption. */
        fun fromDbValue(value: String): PlanChangeDecisionStatus =
            entries.firstOrNull { it.dbValue == value.trim().uppercase() }
                ?: throw IllegalStateException("Unknown plan change decision status: $value")

        /** Parse a caller-supplied status filter, case-insensitively; `null` when unknown. */
        fun parse(value: String?): PlanChangeDecisionStatus? =
            value?.trim()?.uppercase()?.let { normalized -> entries.firstOrNull { it.dbValue == normalized } }

        /** Parse a caller-supplied `/decide` outcome; `null` when unknown or [PENDING_VALIDATION]. */
        fun parseDecision(value: String?): PlanChangeDecisionStatus? =
            parse(value)?.takeIf { it in DECIDABLE }
    }
}
