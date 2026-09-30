package io.whozoss.agentos.git

/**
 * Preparation state of the managed clone backing a namespace's Git association.
 *
 * Distinct from any case-level state: this describes the namespace's own checkout, which every
 * per-case worktree is later derived from.
 */
enum class RepositoryCheckoutStatus {
    /** Clone in progress, or being retried. */
    PREPARING,

    /** Clone completed and verified; case worktrees can be created from it. */
    READY,

    /** Preparation failed. [RepositoryCheckout.failureReason] carries the operator-facing cause. */
    FAILED,
}
