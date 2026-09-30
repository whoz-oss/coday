package io.whozoss.agentos.plugins.git

import com.fasterxml.jackson.databind.JsonNode
import java.nio.file.Path

/**
 * The worktree a GIT integration works in, as the service injects it into each run's configuration.
 *
 * Every value comes from the service's own records: the administrative directory is pinned rather
 * than read from the worktree's `.git` file, which an agent can rewrite. Without all of them the
 * integration is outside a Git workspace and provides no tool.
 */
internal data class GitWorkspaceContext(
    val workingDirectory: Path,
    val gitDir: Path,
    val commonGitDir: Path,
    val repositoryUrl: String,
    val mainBranch: String,
) {
    companion object {
        fun from(config: JsonNode?): GitWorkspaceContext? {
            fun text(key: String): String? = config?.get(key)?.takeIf { it.isTextual }?.asText()?.takeIf { it.isNotBlank() }
            return GitWorkspaceContext(
                workingDirectory = Path.of(text("workingDirectory") ?: return null),
                gitDir = Path.of(text("gitDir") ?: return null),
                commonGitDir = Path.of(text("commonGitDir") ?: return null),
                repositoryUrl = text("repositoryUrl") ?: return null,
                mainBranch = text("mainBranch") ?: return null,
            )
        }
    }
}
