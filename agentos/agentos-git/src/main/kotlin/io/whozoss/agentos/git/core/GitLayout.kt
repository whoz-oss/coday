package io.whozoss.agentos.git.core

/** Names Git itself gives to the parts of a repository that has linked worktrees. */
object GitLayout {
    /** Directory of the common repository holding one administrative directory per linked worktree. */
    const val WORKTREES_DIR: String = "worktrees"

    /** File of a linked worktree's administrative directory that points at the common repository. */
    const val COMMON_DIR_FILE: String = "commondir"

    /** Entry of a worktree that holds, or points at, its Git metadata. */
    const val DOT_GIT: String = ".git"

    /** File of a linked worktree's administrative directory that points back at the worktree. */
    const val GITDIR_FILE: String = "gitdir"

    /** Start of the `.git` file of a linked worktree, followed by its administrative directory. */
    const val GITDIR_POINTER_PREFIX: String = "gitdir: "

    /** Present in an administrative directory when the worktree is locked against pruning. */
    const val LOCKED_FILE: String = "locked"

    /** Reason `git worktree add` writes in [LOCKED_FILE] until the checkout of the new worktree completes. */
    const val INITIALIZING_LOCK_REASON: String = "initializing"

    /** Index of a linked worktree, in its administrative directory. Written once its checkout completes. */
    const val INDEX_FILE: String = "index"

    /** Present in an administrative directory when the worktree holds submodule repositories. */
    const val MODULES_DIR: String = "modules"
}
