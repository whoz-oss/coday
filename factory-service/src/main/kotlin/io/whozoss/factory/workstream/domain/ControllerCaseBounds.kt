package io.whozoss.factory.workstream.domain

/**
 * Strict bounds of the controller case lifecycle surface (Phase 9).
 *
 * The resumption context package persisted on each controller case is a
 * bounded summary rebuilt from the Phase 5 projection — never a raw
 * conversation history. Every section is capped individually and the whole
 * serialized package is hard-capped at [MAX_CONTEXT_SUMMARY_BYTES] UTF-8
 * bytes (the compact-or-reject precedent of
 * `StepQuestionLimits.RESUMPTION_CONTEXT_BYTES`).
 */
object ControllerCaseBounds {
    /** Hard upper bound of the serialized resumption package stored on a case. */
    const val MAX_CONTEXT_SUMMARY_BYTES = 8192

    /** Maximum active workflow summaries carried by the package. */
    const val MAX_WORKFLOW_ITEMS = 10

    /** Maximum open human interaction summaries carried by the package. */
    const val MAX_HUMAN_ACTIONS = 10

    /** Maximum blocker summaries (blocked steps + failed oracles) carried by the package. */
    const val MAX_BLOCKERS = 10

    /** Maximum recent-change summaries carried by the package. */
    const val MAX_RECENT_CHANGES = 10

    /** Maximum length of the operator-supplied compaction reason. */
    const val MAX_COMPACTION_REASON_CHARS = 500
}
