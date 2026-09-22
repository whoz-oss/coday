package io.whozoss.agentos.git

/**
 * Preparation state of the managed clone backing a namespace's Git association.
 *
 * Distinct from any case-level state: this describes the namespace's own checkout, which every
 * per-case worktree is later derived from.
 */
enum class RepositoryCheckoutStatus {
    /** Clone in progress, or being retried. The Namespace Exchange still serves its previous content. */
    PREPARING,

    /** Clone completed and verified; the checkout is the Namespace Exchange root. */
    READY,

    /** Preparation failed. [RepositoryCheckout.failureReason] carries the operator-facing cause. */
    FAILED,
    ;

    val isTerminal: Boolean get() = this == READY || this == FAILED
}
