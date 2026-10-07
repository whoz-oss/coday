package io.whozoss.agentos.plugins.git

import io.whozoss.agentos.git.core.GitCommandResult
import io.whozoss.agentos.git.core.GitCommandRunner
import io.whozoss.agentos.git.core.GitCredentials
import io.whozoss.agentos.git.core.GitInvocation
import io.whozoss.agentos.git.core.GitOutputFormat
import io.whozoss.agentos.git.core.GitPushLease
import io.whozoss.agentos.git.core.GitRefNames
import io.whozoss.agentos.git.core.GitRefs
import java.time.Duration

/**
 * Git operations in the family's worktree or the configured repository, run through the service's
 * hardened runner.
 *
 * Local commands pin `--git-dir` and `--work-tree` from [GitWorkspaceContext]; commands that can
 * run a filter first check the repository configuration. Credentialed commands use the runner's
 * private network context, so nothing an agent wrote in the shared configuration sees the token.
 * A configured repository is read on first use, so a wrong configuration reaches the agent as the
 * tool's answer.
 */
internal class GitWorkspace(
    resolveContext: () -> GitWorkspaceContext,
    private val runner: GitCommandRunner,
    /** Fetch and push, like the service's clone and fetch: `agentos.git.clone-timeout`. */
    private val networkTimeout: Duration,
) {
    constructor(context: GitWorkspaceContext, runner: GitCommandRunner, networkTimeout: Duration) :
        this({ context }, runner, networkTimeout)

    private val context by lazy(resolveContext)

    val mainBranch: String get() = context.mainBranch

    val repositoryUrl: String get() = context.repositoryUrl

    /**
     * The checked-out branch, or null on a detached HEAD. Read as a full ref: `--short` answers
     * `heads/<name>` when a tag has the same name.
     */
    fun branch(): String? {
        val result = runner.run(local("symbolic-ref", "--quiet", "HEAD"))
        return when {
            result is GitCommandResult.Completed && result.successful && !result.truncated -> branchOf(result.stdout.trim())
            result is GitCommandResult.Completed && result.exitCode == 1 -> null
            else -> throw GitToolException("Cannot read the current branch: ${describe(result)}")
        }
    }

    private fun branchOf(ref: String): String =
        ref.takeIf { it.startsWith(GitRefs.HEADS) }?.removePrefix(GitRefs.HEADS)
            ?: throw GitToolException("HEAD points outside the branches: $ref")

    fun head(): String? = commitOf("HEAD")

    /** Where the last fetch or push left the remote branch, or null if it was never seen. */
    fun trackedCommit(branch: String): String? = commitOf(GitRefs.remoteTracking(branch))

    fun status(): List<String> {
        guard()
        val output = successful(
            runner.run(
                // An untracked directory is one entry, as in plain `git status`: a dependency or build
                // directory left out of .gitignore must not overflow the output.
                local("status", "--porcelain=v1", "-z", "--untracked-files=normal", "--ignore-submodules=dirty", "--no-renames"),
            ),
            "read the status",
        )
        return output.split(GitOutputFormat.NUL).filter { it.isNotEmpty() }
    }

    fun createBranch(name: String) {
        requireBranchName(name)
        guard()
        if (commitOf(GitRefs.head(name)) != null) throw GitToolException("Branch '$name' already exists")
        successful(runner.run(local("switch", "--quiet", "--create", name)), "create the branch")
    }

    /**
     * Stage [paths] (every change when empty) and commit them. Returns the new commit. With paths,
     * only they are committed: what someone else staged in a shared worktree stays staged for them.
     */
    fun commit(message: String, paths: List<String>, author: GitForgeAccess.Identity): String {
        guard()
        val only = if (paths.isEmpty()) emptyList() else listOf("--") + paths
        successful(runner.run(local("add", "--all", "--", *paths.ifEmpty { listOf(".") }.toTypedArray())), "stage the changes")
        val staged = runner.run(local("diff", "--cached", "--quiet", "--ignore-submodules=dirty", *only.toTypedArray()))
        if (staged is GitCommandResult.Completed && staged.exitCode == 0) throw GitToolException("Nothing to commit")
        if (staged !is GitCommandResult.Completed || staged.exitCode != 1) {
            throw GitToolException("Cannot inspect the staged changes: ${describe(staged)}")
        }
        successful(
            runner.run(
                local(
                    "-c", "user.name=${author.name}",
                    "-c", "user.email=${author.email}",
                    // A repository setting could otherwise run its signing program.
                    "-c", "commit.gpgsign=false",
                    "commit", "--quiet", "--no-verify", "-m", message, *only.toTypedArray(),
                ),
            ),
            "commit",
        )
        return head() ?: throw GitToolException("The commit did not produce a HEAD")
    }

    fun fetch(branch: String, token: GitCredentials.UsernamePassword): String {
        requireBranchName(branch)
        successful(
            runner.run(
                GitInvocation(
                    listOf("fetch", "--quiet", context.repositoryUrl, "+${GitRefs.head(branch)}:${GitRefs.remoteTracking(branch)}"),
                    gitDir = context.commonGitDir,
                    timeout = networkTimeout,
                    credentials = token,
                ),
            ),
            "fetch '$branch'",
        )
        return trackedCommit(branch) ?: throw GitToolException("The fetch did not record '$branch'")
    }

    /**
     * Push [branch] to the branch of the same name. With [lease], the remote branch may be rewritten
     * only if it is still where the last fetch or push saw it.
     */
    fun push(branch: String, token: GitCredentials.UsernamePassword, lease: Boolean) {
        val leaseOption = if (lease) listOf(GitPushLease.of(branch, trackedCommit(branch).orEmpty())) else emptyList()
        successful(
            runner.run(
                GitInvocation(
                    listOf("push") + leaseOption + listOf("--", context.repositoryUrl, "${GitRefs.head(branch)}:${GitRefs.head(branch)}"),
                    gitDir = context.commonGitDir,
                    timeout = networkTimeout,
                    credentials = token,
                ),
            ),
            "push '$branch'",
        )
    }

    private fun commitOf(ref: String): String? {
        val result = runner.run(local("rev-parse", "--verify", "--quiet", "$ref^{commit}"))
        return when {
            result is GitCommandResult.Completed && result.successful && !result.truncated -> result.stdout.trim()
            result is GitCommandResult.Completed && result.exitCode == 1 -> null
            else -> throw GitToolException("Cannot read $ref: ${describe(result)}")
        }
    }

    private fun requireBranchName(name: String) {
        if (!GitRefNames.isValidBranchName(name)) throw GitToolException("'$name' is not a valid branch name")
    }

    /** Filters and diff drivers from the shared configuration would run during status, add or commit. */
    private fun guard() = runner.assertNoHostileLocalConfig(context.gitDir)

    private fun local(vararg args: String) =
        GitInvocation(
            args.toList(),
            gitDir = context.gitDir,
            workTree = context.workingDirectory,
            workingDirectory = context.workingDirectory,
        )

    private fun successful(result: GitCommandResult, action: String): String {
        if (result is GitCommandResult.Completed && result.successful && !result.truncated) return result.stdout
        throw GitToolException("Could not $action: ${describe(result)}")
    }

    private fun describe(result: GitCommandResult): String =
        when (result) {
            is GitCommandResult.Completed -> result.stderr.trim().ifEmpty { result.stdout.trim() }
                .ifEmpty { "git exited with ${result.exitCode}" }.take(MAX_MESSAGE)
            is GitCommandResult.TimedOut -> "timed out after ${result.timeout}"
            is GitCommandResult.Failed -> result.message.take(MAX_MESSAGE)
        }

    internal companion object {
        /** Longest Git explanation returned to the agent. */
        const val MAX_MESSAGE = 1_000
    }
}
