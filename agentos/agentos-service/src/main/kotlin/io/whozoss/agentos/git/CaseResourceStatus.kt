package io.whozoss.agentos.git

/**
 * Lifecycle of the workspace allocated to a case family.
 *
 * Deliberately separate from [io.whozoss.agentos.sdk.caseFlow.CaseStatus], which describes an
 * agent turn. A case can be idle while its workspace is still preparing, and a workspace can be
 * removed long after the conversation went quiet.
 */
enum class CaseResourceStatus {
    /** Persisted with the case, not yet picked up by a provisioner. */
    REQUESTED,

    /** Worktree being created, setup possibly running. */
    PREPARING,

    /** Usable: the worktree exists and tools may run in it. */
    READY,

    /** Preparation failed; [CaseResourceBinding.failureReason] says why. Retryable. */
    FAILED,

    /** Cleanup in progress. No run may start. */
    DELETING,

    /** The worktree is gone. Git metadata is kept on the binding for history. */
    REMOVED,
    ;

    /** Whether tools may run against this workspace. */
    val isUsable: Boolean get() = this == READY

    /** Whether a run may be held waiting for this workspace to become usable. */
    val isPending: Boolean get() = this == REQUESTED || this == PREPARING
}
