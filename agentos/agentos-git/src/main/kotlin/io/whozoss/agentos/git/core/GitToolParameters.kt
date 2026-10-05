package io.whozoss.agentos.git.core

/**
 * Keys of the Git context a case workspace injects into each run's `GIT` integration.
 *
 * The service writes them and the GIT plugin reads them: both depend on this library, so the
 * contract is spelled once.
 */
object GitToolParameters {
    /** Absolute path of the worktree the tools work in. */
    const val WORKING_DIRECTORY: String = "workingDirectory"

    /** Administrative directory of that worktree, pinned by name instead of read from its `.git` file. */
    const val GIT_DIR: String = "gitDir"

    /** Common repository shared by the namespace's worktrees. */
    const val COMMON_GIT_DIR: String = "commonGitDir"

    /** Remote the family was equipped with. */
    const val REPOSITORY_URL: String = "repositoryUrl"

    /** Main branch the family was equipped with. */
    const val MAIN_BRANCH: String = "mainBranch"
}
