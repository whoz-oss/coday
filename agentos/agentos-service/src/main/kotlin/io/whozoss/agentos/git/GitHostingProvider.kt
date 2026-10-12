package io.whozoss.agentos.git

/**
 * Observes the pull request of a workspace branch on the forge that hosts its repository.
 *
 * Only the pull request fields of the returned summary are read: the branch state is observed
 * locally by [GitWorkspaceStatusService].
 */
interface GitHostingProvider {
    /**
     * The pull request of [branch] in the repository [settings] name. [headSha], when given, lets a
     * branch checked out under a local alias be matched by its exact head commit.
     */
    fun inspect(settings: GitRepositorySettings, branch: String, headSha: String? = null): GitWorkspaceSummary
}
