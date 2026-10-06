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
    private fun settings(binding: CaseResourceBinding): GitRepositorySettings =
        binding.settings ?: throw ConflictException("No readable settings were recorded for this workspace")

    private fun commonGitDir(binding: CaseResourceBinding): Path =
        storage.namespaceGitDirectory(binding.namespaceId).toAbsolutePath().normalize()

    /** Git's administrative directory of the family's worktree, pinned rather than read from its `.git` file. */
    private fun worktreeGitDir(binding: CaseResourceBinding): Path = commonGitDir(binding).worktreeRegistration(binding.rootCaseId)

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

    fun refresh(binding: CaseResourceBinding, path: Path): CaseResourceBinding {
        // Network observation must not block message admission or file mutations.
        val current = WorkspaceLifecycleLocks.tryWithRoot(binding.rootCaseId, onBusy = { null }) {
            bindings.findByRootCaseId(binding.rootCaseId)
        } ?: return binding
        if (current.status != CaseResourceStatus.READY) return current
        var observedBranch = current.branchName
        var state = GitWorkspaceSummary(observedAt = clock.instant())
        try {
            // Everything below observes `current`, the state re-read under the lock: `binding` may
            // already be stale when the caller handed it over.
            val settings = settings(current)
            val common = commonGitDir(current)
            runner.assertNoHostileLocalConfig(common)
            val administrativeDir = worktreeGitDir(current)
            val symbolic = runner.run(GitInvocation(listOf("symbolic-ref", "--quiet", "HEAD"), gitDir = administrativeDir))
            check(symbolic is GitCommandResult.Completed) { "Cannot inspect the worktree branch" }
            observedBranch = when (symbolic.exitCode) {
                0 -> symbolic.stdout.trim()
                    .also { check(it.startsWith(GitRefs.HEADS)) { "HEAD resolves outside ${GitRefs.HEADS}" } }
                    .removePrefix(GitRefs.HEADS)

                1 -> null // detached HEAD, not an error and not a branch named HEAD
                else -> error("Cannot inspect the worktree branch")
            }
            val head = runner.runOrThrow(
                GitInvocation(listOf("rev-parse", "--verify", "HEAD^{commit}"), gitDir = administrativeDir),
            )
            val dirty = isDirty(current, path)
            state = state.copy(headSha = head, dirty = dirty)
            val branch = observedBranch
            if (branch == null) {
                state = state.copy(branchState = BranchState.DETACHED, prState = PrState.NONE)
                return publish(current, null, state)
            }
            val remote = runner.runOrThrow(
                GitInvocation(
                    listOf("ls-remote", settings.repositoryUrl, GitRefs.head(branch)),
                    gitDir = common,
                    credentials = accounts.resolve(settings),
                ),
            ).lineSequence().firstOrNull { it.isNotBlank() }?.substringBefore('\t')
            if (remote == null) {
                state = state.copy(branchState = BranchState.LOCAL_ONLY)
            } else {
                // Only the objects are needed to count unpushed commits. Never update the agent's
                // refs/remotes/origin/*: `push --force-with-lease` uses them as its expected value.
                runner.runOrThrow(
                    GitInvocation(
                        listOf(
                            "fetch", "--quiet", settings.repositoryUrl,
                            "+${GitRefs.head(branch)}:${GitRefs.AGENTOS_OBSERVED}${current.rootCaseId}",
                        ),
                        gitDir = common,
                        credentials = accounts.resolve(settings),
                    ),
                )
                val ahead = runner.runOrThrow(
                    GitInvocation(listOf("rev-list", "--count", "$remote..$head"), gitDir = common),
                ).toInt()
                state = state.copy(
                    branchState = if (ahead > 0) BranchState.UNPUSHED_COMMITS else BranchState.PUSHED,
                    remoteSha = remote,
                    unpushedCommits = ahead,
                )
            }
            // A PR checkout can use a local alias (e.g. pr-1301) instead of its remote branch name.
            // Do not infer a PR from the untouched starting commit shared by every new workspace.
            val pr = hosting.inspect(settings, branch, head.takeUnless { it == current.baseSha })
            state = state.copy(prState = pr.prState, prNumber = pr.prNumber, prUrl = pr.prUrl, prHeadSha = pr.prHeadSha)
        } catch (e: Exception) {
            // Never translate unavailable/stale data into NONE or CLOSED.
            // Network libraries may include credentials in exception messages or causes.
            logger.warn(e) { "Git status unavailable for case ${current.rootCaseId}" }
            state = state.copy(prState = PrState.UNKNOWN, error = STATUS_UNAVAILABLE)
        }
        return publish(current, observedBranch, state)
    }

    private fun publish(binding: CaseResourceBinding, branch: String?, state: GitWorkspaceSummary): CaseResourceBinding =
        WorkspaceLifecycleLocks.tryWithRoot(binding.rootCaseId, onBusy = { binding }) {
            val fresh = bindings.findByRootCaseId(binding.rootCaseId) ?: return@tryWithRoot binding
            // A deletion or a newer observer may have won while the network call was running.
            if (fresh.status != CaseResourceStatus.READY ||
                (fresh.summary?.observedAt?.isAfter(state.observedAt) == true)) return@tryWithRoot fresh
            bindings.update(fresh.copy(branchName = branch, summary = state))
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
        /** Fixed public wording: an exception message may carry credentials. */
        const val STATUS_UNAVAILABLE = "Git status unavailable. Check repository access and service account settings."
    }
}
