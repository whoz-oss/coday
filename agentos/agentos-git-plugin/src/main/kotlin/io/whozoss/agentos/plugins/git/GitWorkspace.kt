package io.whozoss.agentos.plugins.git

import io.whozoss.agentos.git.core.GitCommandResult
import io.whozoss.agentos.git.core.GitCommandRunner
import io.whozoss.agentos.git.core.GitInvocation
import io.whozoss.agentos.git.core.GitRefNames
import java.time.Duration

/**
 * Git operations in the family's worktree, run through the service's hardened runner.
 *
 * Local commands pin `--git-dir` and `--work-tree` from [GitWorkspaceContext]; commands that can
 * run a filter first check the repository configuration. Credentialed commands use the runner's
 * private network context, so nothing an agent wrote in the shared configuration sees the token.
 */
internal class GitWorkspace(
    private val context: GitWorkspaceContext,
    private val runner: GitCommandRunner,
    private val networkTimeout: Duration = Duration.ofMinutes(10),
) {
    val mainBranch: String get() = context.mainBranch

    val repositoryUrl: String get() = context.repositoryUrl

    /** The checked-out branch, or null on a detached HEAD. */
    fun branch(): String? {
        val result = runner.run(local("symbolic-ref", "--quiet", "--short", "HEAD"))
        return when {
            result is GitCommandResult.Completed && result.successful && !result.truncated -> result.stdout.trim()
            result is GitCommandResult.Completed && result.exitCode == 1 -> null
            else -> throw GitToolException("Cannot read the current branch: ${describe(result)}")
        }
    }

    fun head(): String? = commitOf("HEAD")

    /** Where the last fetch or push left the remote branch, or null if it was never seen. */
    fun trackedCommit(branch: String): String? = commitOf("refs/remotes/origin/$branch")

    fun status(): List<String> {
        guard()
        val output = successful(
            runner.run(
                local("status", "--porcelain=v1", "-z", "--untracked-files=all", "--ignore-submodules=dirty", "--no-renames"),
            ),
            "read the status",
        )
        return output.split('\u0000').filter { it.isNotEmpty() }
    }

    fun createBranch(name: String) {
        requireBranchName(name)
        guard()
        if (commitOf("refs/heads/$name") != null) throw GitToolException("Branch '$name' already exists")
        successful(runner.run(local("switch", "--quiet", "--create", name)), "create the branch")
    }

    /** Stage [paths] (every change when empty) and commit them. Returns the new commit. */
    fun commit(message: String, paths: List<String>, author: GitForgeAccess.Identity): String {
        guard()
        successful(runner.run(local("add", "--all", "--", *paths.ifEmpty { listOf(".") }.toTypedArray())), "stage the changes")
        val staged = runner.run(local("diff", "--cached", "--quiet", "--ignore-submodules=dirty"))
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
                    "commit", "--quiet", "--no-verify", "-m", message,
                ),
            ),
            "commit",
        )
        return head() ?: throw GitToolException("The commit did not produce a HEAD")
    }

    fun fetch(branch: String, token: GitForgeAccess.Token): String {
        requireBranchName(branch)
        successful(
            runner.run(
                GitInvocation(
                    listOf("fetch", "--quiet", context.repositoryUrl, "+refs/heads/$branch:refs/remotes/origin/$branch"),
                    gitDir = context.commonGitDir,
                    timeout = networkTimeout,
                    credentials = token.git,
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
    fun push(branch: String, token: GitForgeAccess.Token, lease: Boolean) {
        val leaseOption = if (lease) listOf("--force-with-lease=refs/heads/$branch:${trackedCommit(branch).orEmpty()}") else emptyList()
        successful(
            runner.run(
                GitInvocation(
                    listOf("push") + leaseOption + listOf("--", context.repositoryUrl, "refs/heads/$branch:refs/heads/$branch"),
                    gitDir = context.commonGitDir,
                    timeout = networkTimeout,
                    credentials = token.git,
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
        GitInvocation(args.toList(), gitDir = context.gitDir, workTree = context.workingDirectory, workingDirectory = context.workingDirectory)

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

    private companion object {
        const val MAX_MESSAGE = 1_000
    }
}
