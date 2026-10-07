package io.whozoss.agentos.git

import io.whozoss.agentos.exchange.ExchangeStorageService
import io.whozoss.agentos.exception.ConflictException
import io.whozoss.agentos.git.core.GitCommandResult
import io.whozoss.agentos.git.core.GitCommandRunner
import io.whozoss.agentos.git.core.GitInvocation
import io.whozoss.agentos.git.core.GitRefs
import mu.KLogging
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty
import org.springframework.stereotype.Service
import java.nio.file.Path
import java.time.Clock

@Service
@ConditionalOnProperty(prefix = "agentos.git.workspaces", name = ["enabled"], havingValue = "true")
class GitWorkspaceStatusService(
    private val bindings: CaseResourceBindingService,
    private val storage: ExchangeStorageService,
    private val runner: GitCommandRunner,
    private val accounts: GitServiceAccountResolver,
    private val hosting: GitHostingProvider,
    private val clock: Clock,
) {
    /** The settings frozen when the family was equipped, never the namespace's current ones. */
    fun settings(binding: CaseResourceBinding): GitRepositorySettings =
        binding.settings ?: throw ConflictException("No readable settings were recorded for this workspace")

    fun commonGitDir(binding: CaseResourceBinding): Path =
        storage.namespaceGitDirectory(binding.namespaceId).toAbsolutePath().normalize()

    /** Git's administrative directory of the family's worktree, pinned rather than read from its `.git` file. */
    fun worktreeGitDir(binding: CaseResourceBinding): Path =
        commonGitDir(binding).worktreeRegistration(binding.rootCaseId)

    fun view(root: GitExchangeRoot): CaseWorkspaceView {
        val binding = root.binding ?: return CaseWorkspaceView(equipped = false)
        return CaseWorkspaceView(
            equipped = true,
            rootCaseId = binding.rootCaseId,
            status = binding.status,
            branchName = binding.branchName,
            failureReason = binding.failureReason,
            cleanupReason = binding.cleanupReason,
            git = binding.summary,
        )
    }

    fun refresh(binding: CaseResourceBinding, path: Path): CaseResourceBinding =
        // Network observation must not block message admission or file mutations.
        WorkspaceLifecycleLocks
            .tryWithRoot(binding.rootCaseId, onBusy = { null }) { bindings.findByRootCaseId(binding.rootCaseId) }
            ?.let { current ->
                if (current.status == CaseResourceStatus.READY) publish(current, observe(current, path)) else current
            }
            ?: binding

    /** What one observation found: the branch HEAD is on, and the summary of everything it could read. */
    private data class Observation(
        val branch: String?,
        val summary: GitWorkspaceSummary,
    ) {
        val failed: Boolean get() = summary.error != null
    }

    /**
     * Observe [current], the binding re-read under the lock: the caller's copy may already be stale.
     * Each step reads more, and a failure keeps what the earlier steps read. A detached HEAD has no
     * remote branch and no pull request to look for.
     */
    private fun observe(current: CaseResourceBinding, path: Path): Observation =
        listOf<(Observation) -> Observation>(
            { observeBranch(current, it) },
            { observeHead(current, path, it) },
            { progress -> progress.branch?.let { observeRemote(current, it, progress) } ?: progress },
            { progress -> progress.branch?.let { observePullRequest(current, it, progress) } ?: progress },
        ).fold(Observation(current.branchName, GitWorkspaceSummary(observedAt = clock.instant()))) { progress, step ->
            if (progress.failed) progress else attempt(current, progress, step)
        }

    private fun attempt(
        current: CaseResourceBinding,
        progress: Observation,
        step: (Observation) -> Observation,
    ): Observation =
        try {
            step(progress)
        } catch (e: Exception) {
            // Never translate unavailable/stale data into NONE or CLOSED. Fixed public wording: the
            // persisted error is returned by the API. The log keeps the exception: the runner and
            // GitHubApi never put credentials in it.
            logger.warn(e) { "Git status unavailable for case ${current.rootCaseId}" }
            progress.copy(summary = progress.summary.copy(prState = PrState.UNKNOWN, error = STATUS_UNAVAILABLE))
        }

    /** The branch HEAD is on. A family without readable settings is not observed. */
    private fun observeBranch(current: CaseResourceBinding, progress: Observation): Observation {
        settings(current)
        runner.assertNoHostileLocalConfig(commonGitDir(current))
        val symbolic = runner.run(
            GitInvocation(listOf("symbolic-ref", "--quiet", "HEAD"), gitDir = worktreeGitDir(current)),
        )
        check(symbolic is GitCommandResult.Completed) { "Cannot inspect the worktree branch" }
        val branch = when (symbolic.exitCode) {
            SYMBOLIC_REF_FOUND ->
                symbolic.stdout.trim()
                    .also { check(it.startsWith(GitRefs.HEADS)) { "HEAD resolves outside ${GitRefs.HEADS}" } }
                    .removePrefix(GitRefs.HEADS)
            // A detached HEAD: not an error, and not a branch named HEAD.
            SYMBOLIC_REF_DETACHED -> null
            else -> error("Cannot inspect the worktree branch")
        }
        return progress.copy(branch = branch)
    }

    /** The commit and the local changes of the worktree. */
    private fun observeHead(current: CaseResourceBinding, path: Path, progress: Observation): Observation {
        val head = runner.runOrThrow(
            GitInvocation(listOf("rev-parse", "--verify", "HEAD^{commit}"), gitDir = worktreeGitDir(current)),
        )
        val summary = progress.summary.copy(headSha = head, dirty = isDirty(current, path))
        return progress.copy(
            summary = if (progress.branch == null) {
                summary.copy(branchState = BranchState.DETACHED, prState = PrState.NONE)
            } else {
                summary
            },
        )
    }

    /** Whether the remote holds [branch], and how many of the worktree's commits it lacks. */
    private fun observeRemote(current: CaseResourceBinding, branch: String, progress: Observation): Observation {
        val settings = settings(current)
        val head = progress.summary.headSha
        val remote = runner.runOrThrow(
            GitInvocation(
                listOf("ls-remote", settings.repositoryUrl, GitRefs.head(branch)),
                gitDir = commonGitDir(current),
                credentials = accounts.resolve(settings),
            ),
        ).lineSequence().firstOrNull { it.isNotBlank() }?.substringBefore('\t')
        val summary = when (remote) {
            null -> progress.summary.copy(branchState = BranchState.LOCAL_ONLY)
            // Nothing to fetch: the remote is exactly at the worktree's commit.
            head -> progress.summary.copy(branchState = BranchState.PUSHED, remoteSha = remote, unpushedCommits = 0)
            else -> compareWithRemote(current, settings, branch, progress.summary)
        }
        return progress.copy(summary = summary)
    }

    /**
     * Fetch [branch] into the family's observed ref and count the worktree's commits it lacks. The count
     * uses the fetched commit rather than the one listed before: the remote may move in between.
     */
    private fun compareWithRemote(
        current: CaseResourceBinding,
        settings: GitRepositorySettings,
        branch: String,
        summary: GitWorkspaceSummary,
    ): GitWorkspaceSummary {
        val common = commonGitDir(current)
        val observed = GitRefs.AGENTOS_OBSERVED + current.rootCaseId
        // Only the objects are needed to count unpushed commits. Never update the agent's
        // refs/remotes/origin/*: `push --force-with-lease` uses them as its expected value.
        runner.runOrThrow(
            GitInvocation(
                listOf("fetch", "--quiet", settings.repositoryUrl, "+${GitRefs.head(branch)}:$observed"),
                gitDir = common,
                credentials = accounts.resolve(settings),
            ),
        )
        val remote = runner.runOrThrow(
            GitInvocation(listOf("rev-parse", "--verify", "$observed^{commit}"), gitDir = common),
        )
        val ahead = runner.runOrThrow(
            GitInvocation(listOf("rev-list", "--count", "$remote..${summary.headSha}"), gitDir = common),
        ).toInt()
        return summary.copy(
            branchState = if (ahead > 0) BranchState.UNPUSHED_COMMITS else BranchState.PUSHED,
            remoteSha = remote,
            unpushedCommits = ahead,
        )
    }

    /** The pull request of [branch], as the forge reports it. */
    private fun observePullRequest(current: CaseResourceBinding, branch: String, progress: Observation): Observation {
        // A PR checkout can use a local alias (e.g. pr-1301) instead of its remote branch name.
        // Do not infer a PR from the untouched starting commit shared by every new workspace.
        val head = progress.summary.headSha.takeUnless { it == current.baseSha }
        val pr = hosting.inspect(settings(current), branch, head)
        return progress.copy(
            summary = progress.summary.copy(
                prState = pr.prState,
                prNumber = pr.prNumber,
                prUrl = pr.prUrl,
                prHeadSha = pr.prHeadSha,
            ),
        )
    }

    private fun publish(binding: CaseResourceBinding, observation: Observation): CaseResourceBinding =
        WorkspaceLifecycleLocks.tryWithRoot(binding.rootCaseId, onBusy = { binding }) {
            val fresh = bindings.findByRootCaseId(binding.rootCaseId)
            // A deletion or a newer observer may have won while the network calls were running.
            when {
                fresh == null -> binding
                fresh.status != CaseResourceStatus.READY -> fresh
                fresh.summary?.observedAt?.isAfter(observation.summary.observedAt) == true -> fresh
                else -> bindings.update(fresh.copy(branchName = observation.branch, summary = observation.summary))
            }
        }

    private fun isDirty(binding: CaseResourceBinding, path: Path): Boolean {
        // Submodule contents are not inspected: that would run the submodule's own filters.
        // `--no-optional-locks` keeps the observer from refreshing the index the agent is using.
        val result = runner.run(
            GitInvocation(
                listOf(
                    "--no-optional-locks", "status", "--porcelain",
                    "--untracked-files=normal", "--ignore-submodules=dirty",
                ),
                gitDir = worktreeGitDir(binding),
                workTree = path,
            ),
        )
        check(result is GitCommandResult.Completed && result.successful) { "Cannot inspect local Git changes" }
        // Any porcelain entry proves dirty. A bounded, truncated list is sufficient for this boolean.
        return result.stdout.isNotEmpty()
    }

    companion object : KLogging() {
        /** Exit code of `git symbolic-ref --quiet` when HEAD is on a branch. */
        private const val SYMBOLIC_REF_FOUND = 0

        /** Exit code of `git symbolic-ref --quiet` on a detached HEAD. */
        private const val SYMBOLIC_REF_DETACHED = 1

        /** Fixed public wording: the persisted error is returned by the API. */
        const val STATUS_UNAVAILABLE = "Git status unavailable. Check repository access and service account settings."
    }
}
