package io.whozoss.agentos.git

import com.fasterxml.jackson.databind.ObjectMapper
import io.whozoss.agentos.exception.ConflictException
import io.whozoss.agentos.git.core.GitHubApi
import io.whozoss.agentos.git.core.GitHubForgeDeploymentReader
import io.whozoss.agentos.git.core.GitHubRepository
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty
import org.springframework.stereotype.Service

/** Uses the Case family's namespace service account, never a caller-supplied token. */
@Service
@ConditionalOnProperty(prefix = "agentos.git.workspaces", name = ["enabled"], havingValue = "true")
class GitHubForgeDeploymentService(
    private val status: GitWorkspaceStatusService,
    private val accounts: GitServiceAccountResolver,
    mapper: ObjectMapper,
) {
    private val reader = GitHubForgeDeploymentReader(GitHubApi(mapper = mapper))

    fun read(
        root: GitExchangeRoot,
        workCommit: String,
        integrationBranch: String,
        integrationCommit: String?,
        target: String,
    ): GitHubForgeDeploymentReader.Evidence? {
        val binding = root.binding ?: throw ConflictException("This Case has no Git repository")
        if (binding.status != CaseResourceStatus.READY) throw ConflictException("The worktree is unavailable")
        val settings = status.settings(binding)
        val repository = GitHubRepository.fromRemoteUrl(settings.repositoryUrl)
            ?: throw ConflictException("Forge hosted deployment needs a GitHub repository")
        val token = accounts.resolve(settings).secret
        return if (integrationCommit == null) {
            reader.readLatest(repository, token, workCommit, integrationBranch, target)
        } else {
            reader.read(repository, token, workCommit, integrationBranch, integrationCommit, target)
        }
    }
}
