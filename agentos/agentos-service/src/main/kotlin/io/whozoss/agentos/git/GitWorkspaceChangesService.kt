package io.whozoss.agentos.git

import io.whozoss.agentos.exception.ConflictException
import io.whozoss.agentos.exception.ResourceNotFoundException
import mu.KLogging
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty
import org.springframework.stereotype.Service

/** The Git state and local changes of a case's workspace, as the Files panel shows them. */
@Service
@ConditionalOnProperty(prefix = "agentos.git.workspaces", name = ["enabled"], havingValue = "true")
class GitWorkspaceChangesService(
    private val status: GitWorkspaceStatusService,
    private val diffs: ExchangeGitDiff,
) {
    fun environment(root: GitExchangeRoot): ExchangeEnvironment {
        val binding = root.binding ?: return ExchangeEnvironment(false)
        val view = ExchangeEnvironment(true, binding.status.name, root.repositoryPath.toAbsolutePath().normalize().toString(), git = status.summary(binding))
        if (binding.status != CaseResourceStatus.READY) return view
        val observed = inspect(view, target(root))
        // The forge monitor is asynchronous; never attach its previous branch's PR to a new HEAD.
        return observed.copy(git = observed.git.takeIf { observed.branch == binding.branchName })
    }

    fun diff(
        root: GitExchangeRoot,
        path: String,
    ): ExchangeFileDiff = diffs.file(target(root), path)

    private fun inspect(view: ExchangeEnvironment, target: ExchangeGitTarget): ExchangeEnvironment = try {
        view.copy(branch = diffs.branch(target), changes = diffs.changes(target))
    } catch (e: Exception) {
        logger.warn(e) { "Cannot inspect the Git changes of ${target.path}" }
        view.copy(error = "Cannot inspect Git changes. Retry shortly.")
    }

    private fun target(root: GitExchangeRoot): ExchangeGitTarget {
        val b = root.binding ?: throw ResourceNotFoundException("This case has no repository")
        if (b.status != CaseResourceStatus.READY) throw ConflictException("The worktree is not available yet")
        return ExchangeGitTarget(root.repositoryPath.toAbsolutePath().normalize(), status.worktreeGitDir(b), status.commonGitDir(b), status.settings(b).mainBranch, b.baseSha)
    }

    companion object : KLogging()
}
