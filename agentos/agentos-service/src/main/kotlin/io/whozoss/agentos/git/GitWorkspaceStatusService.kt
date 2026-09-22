package io.whozoss.agentos.git

import com.fasterxml.jackson.databind.ObjectMapper
import io.whozoss.agentos.exchange.ExchangeStorageService
import org.springframework.stereotype.Service
import java.nio.file.Path
import java.time.Instant

@Service
class GitWorkspaceStatusService(
    private val bindings: CaseResourceBindingService,
    private val associations: GitRepositoryAssociationService,
    private val storage: ExchangeStorageService,
    private val runner: GitCommandRunner,
    private val accounts: GitServiceAccountResolver,
    private val hosting: GitHostingProvider,
    private val mapper: ObjectMapper,
) {
    fun settings(binding: CaseResourceBinding): GitRepositorySettings = binding.settingsJson?.let {
        mapper.readValue(it, GitRepositorySettings::class.java)
    } ?: requireNotNull(associations.findSettings(binding.namespaceId)) { "Repository association is missing" }

    fun commonGitDir(binding: CaseResourceBinding): Path =
        storage.namespaceGitDirectory(binding.namespaceId).toAbsolutePath().normalize()

    fun worktreeGitDir(binding: CaseResourceBinding): Path = commonGitDir(binding).resolve("worktrees/${binding.rootCaseId}")

    fun summary(binding: CaseResourceBinding): GitWorkspaceSummary? = binding.summaryJson?.let {
        mapper.readValue(it, GitWorkspaceSummary::class.java)
    }

    fun view(root: ResolvedExchangeRoot): CaseWorkspaceView {
        val b = root.binding ?: return CaseWorkspaceView(equipped = false)
        return CaseWorkspaceView(true, b.rootCaseId, b.status.name, b.branchName, b.failureReason,
            b.cleanupReason, summary(b))
    }

    fun refresh(binding: CaseResourceBinding, path: Path): CaseResourceBinding = WorkspaceLifecycleLocks.withRoot(binding.rootCaseId) {
        val current = bindings.findByRootCaseId(binding.rootCaseId) ?: binding
        if (current.status != CaseResourceStatus.READY) return@withRoot current
        var observedBranch = current.branchName
        var state = GitWorkspaceSummary(observedAt = Instant.now())
        try {
            val settings = settings(binding)
            val common = commonGitDir(binding)
            runner.assertNoHostileLocalConfig(common)
            val administrativeDir = worktreeGitDir(binding)
            val symbolic = runner.run(GitInvocation(listOf("symbolic-ref", "--quiet", "HEAD"), gitDir = administrativeDir))
            check(symbolic is GitCommandResult.Completed) { "Cannot inspect the worktree branch" }
            observedBranch = when (symbolic.exitCode) {
                0 -> symbolic.stdout.trim().also { check(it.startsWith("refs/heads/")) }.removePrefix("refs/heads/")
                1 -> null // detached HEAD, not an error and not a branch named HEAD
                else -> error("Cannot inspect the worktree branch")
            }
            val head = runner.runOrThrow(GitInvocation(listOf("rev-parse", "--verify", "HEAD^{commit}"), gitDir = administrativeDir))
            val dirty = runner.runOrThrow(GitInvocation(listOf("status", "--porcelain", "--untracked-files=all"),
                gitDir = worktreeGitDir(binding), workTree = path)).isNotEmpty()
            state = state.copy(headSha = head, dirty = dirty)
            val branch = observedBranch
            if (branch == null) {
                state = state.copy(branchState = "DETACHED", prState = "NONE")
                return@withRoot bindings.update(current.copy(branchName = null, summaryJson = mapper.writeValueAsString(state)))
            }
            val remote = runner.runOrThrow(GitInvocation(listOf("ls-remote", settings.repositoryUrl, "refs/heads/$branch"),
                gitDir = common, credentials = accounts.resolve(settings))).lineSequence().firstOrNull { it.isNotBlank() }?.substringBefore('\t')
            if (remote == null) {
                state = state.copy(branchState = "LOCAL_ONLY")
            } else {
                runner.runOrThrow(GitInvocation(listOf("fetch", "--quiet", settings.repositoryUrl,
                    "+refs/heads/$branch:refs/remotes/origin/$branch"), gitDir = common, credentials = accounts.resolve(settings)))
                val ahead = runner.runOrThrow(GitInvocation(listOf("rev-list", "--count", "$remote..$head"), gitDir = common)).toInt()
                state = state.copy(branchState = if (ahead > 0) "UNPUSHED_COMMITS" else "PUSHED", remoteSha = remote, unpushedCommits = ahead)
            }
            // A PR checkout can use a local alias (e.g. pr-1301) instead of its remote branch name.
            // Do not infer a PR from the untouched starting commit shared by every new workspace.
            val pr = hosting.inspect(settings, branch, head.takeUnless { it == current.baseSha })
            state = state.copy(prState = pr.prState, prNumber = pr.prNumber, prUrl = pr.prUrl, prHeadSha = pr.prHeadSha)
        } catch (e: Exception) {
            // Never translate unavailable/stale data into NONE or CLOSED.
            state = state.copy(prState = "UNKNOWN", error = e.message?.take(500))
        }
        val fresh = bindings.findByRootCaseId(binding.rootCaseId) ?: return@withRoot binding
        bindings.update(fresh.copy(branchName = observedBranch, summaryJson = mapper.writeValueAsString(state)))
    }
}
