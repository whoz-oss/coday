package io.whozoss.agentos.plugins.git

import com.fasterxml.jackson.databind.JsonNode
import io.whozoss.agentos.git.core.GitCommandResult
import io.whozoss.agentos.git.core.GitCommandRunner
import io.whozoss.agentos.git.core.GitInvocation
import io.whozoss.agentos.git.core.GitToolParameters
import java.nio.file.Path

/**
 * The repository a GIT integration works in.
 *
 * In a case Git workspace the service injects every value into each run's configuration, from its
 * own records: the administrative directory is pinned rather than read from the worktree's `.git`
 * file, which an agent can rewrite. Outside a workspace the integration is an ordinary one: its
 * configuration names the repository root, its remote and its main branch, and the Git
 * directories are read from that repository.
 */
internal data class GitWorkspaceContext(
    val workingDirectory: Path,
    val gitDir: Path,
    val commonGitDir: Path,
    val repositoryUrl: String,
    val mainBranch: String,
) {
    companion object {
        /** Main branch of a configured repository whose integration names none. */
        const val DEFAULT_MAIN_BRANCH: String = "main"

        /** The context a case Git workspace injects, or null when one of its values is missing. */
        fun from(config: JsonNode?): GitWorkspaceContext? =
            GitWorkspaceContext(
                workingDirectory = Path.of(text(config, GitToolParameters.WORKING_DIRECTORY) ?: return null),
                gitDir = Path.of(text(config, GitToolParameters.GIT_DIR) ?: return null),
                commonGitDir = Path.of(text(config, GitToolParameters.COMMON_GIT_DIR) ?: return null),
                repositoryUrl = text(config, GitToolParameters.REPOSITORY_URL) ?: return null,
                mainBranch = text(config, GitToolParameters.MAIN_BRANCH) ?: return null,
            )

        /** The repository root a configuration names outside a workspace, or null when it names none. */
        fun configuredDirectory(config: JsonNode?): Path? =
            text(config, GitToolParameters.WORKING_DIRECTORY)?.let { Path.of(it).normalize() }

        /** The remote a configuration names outside a workspace, or null when it names none. */
        fun configuredRepositoryUrl(config: JsonNode?): String? = text(config, GitToolParameters.REPOSITORY_URL)

        /**
         * Read the Git directories of a configured repository, which must be the root of a non-bare
         * working tree.
         *
         * The remote and the main branch come from the integration only: the repository's own
         * configuration is writable by an agent, and the remote decides where the user's token goes.
         */
        fun discover(
            workingDirectory: Path,
            config: JsonNode?,
            runner: GitCommandRunner,
        ): GitWorkspaceContext {
            fun read(vararg args: String): String {
                val result = runner.run(GitInvocation(args.toList(), workingDirectory = workingDirectory))
                if (result is GitCommandResult.Completed && result.successful && !result.truncated) return result.stdout.trim()
                val detail = (result as? GitCommandResult.Completed)?.stderr?.trim()?.take(MAX_DETAIL).orEmpty()
                throw GitToolException("$workingDirectory is not the root of a Git working tree. $detail".trim())
            }

            val topLevel = Path.of(read("rev-parse", "--show-toplevel"))
            val root = runCatching { workingDirectory.toRealPath() }.getOrNull()
            if (root == null || topLevel.toRealPath() != root) {
                throw GitToolException("$workingDirectory is not the root of a Git working tree")
            }
            return GitWorkspaceContext(
                workingDirectory = workingDirectory,
                gitDir = Path.of(read("rev-parse", "--absolute-git-dir")),
                commonGitDir = Path.of(read("rev-parse", "--path-format=absolute", "--git-common-dir")),
                repositoryUrl =
                    configuredRepositoryUrl(config)
                        ?: throw GitToolException("Configure repositoryUrl on this GIT integration to use it outside a Git workspace"),
                mainBranch = text(config, GitToolParameters.MAIN_BRANCH) ?: DEFAULT_MAIN_BRANCH,
            )
        }

        private fun text(
            config: JsonNode?,
            key: String,
        ): String? = config?.get(key)?.takeIf { it.isTextual }?.asText()?.takeIf { it.isNotBlank() }

        /** Keeps a Git error readable in a tool answer. */
        private const val MAX_DETAIL = 500
    }
}
