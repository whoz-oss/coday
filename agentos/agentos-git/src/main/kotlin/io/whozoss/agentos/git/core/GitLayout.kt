package io.whozoss.agentos.git.core

/** Names Git itself gives to the parts of a repository that has linked worktrees. */
object GitLayout {
    /** Directory of the common repository holding one administrative directory per linked worktree. */
    const val WORKTREES_DIR: String = "worktrees"

    /** File of a linked worktree's administrative directory that points at the common repository. */
    const val COMMON_DIR_FILE: String = "commondir"
}
