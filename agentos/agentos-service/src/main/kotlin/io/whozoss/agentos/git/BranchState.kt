package io.whozoss.agentos.git

/** Where a workspace branch stands against the remote, as last observed. */
enum class BranchState {
    /** HEAD is not on a branch: there is nothing to push nor any PR to show. */
    DETACHED,

    /** The branch exists only in the workspace. */
    LOCAL_ONLY,

    /** The remote branch holds every commit of the workspace branch. */
    PUSHED,

    /** The workspace branch has commits the remote branch lacks. */
    UNPUSHED_COMMITS,

    /** Nothing could be observed. */
    UNKNOWN,
}
