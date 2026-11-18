package io.whozoss.agentos.git

import io.whozoss.agentos.exception.ConflictException
import io.whozoss.agentos.git.core.GitHubRepository
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty
import org.springframework.stereotype.Service

/** Reads the exact open PR head for a Case's Git branch using its service account. */
@Service
@ConditionalOnProperty(prefix = "agentos.git.workspaces", name = ["enabled"], havingValue = "true")
class GitHubPinnedPullRequestService(
    private val status: GitWorkspaceStatusService,
    private val pullRequests: GitHubPullRequests,
) {
    data class Evidence(
        val repository: String,
        val branch: String,
        val headCommit: String,
        val number: Int,
        val url: String,
    )

    fun read(root: GitExchangeRoot, headCommit: String): Evidence? {
        if (!COMMIT.matches(headCommit)) return null
        val binding = root.binding ?: throw ConflictException("This Case has no Git repository")
        if (binding.status != CaseResourceStatus.READY) throw ConflictException("The worktree is unavailable")
        val branch = binding.branchName?.takeIf { it.isNotBlank() } ?: return null
        val settings = status.settings(binding)
        val repository = GitHubRepository.fromRemoteUrl(settings.repositoryUrl) ?: return null
        val observed = pullRequests.inspect(settings, branch, headCommit)
        if (observed.prState != PrState.OPEN || observed.prHeadSha != headCommit) return null
        val number = observed.prNumber?.takeIf { it > 0 } ?: return null
        val url = observed.prUrl?.takeIf { it.isNotBlank() } ?: return null
        return Evidence(repository.fullName, branch, headCommit, number, url)
    }

    private companion object {
        val COMMIT = Regex("^[0-9a-f]{40}$")
    }
}
