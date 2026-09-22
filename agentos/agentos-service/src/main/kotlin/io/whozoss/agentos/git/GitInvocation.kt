package io.whozoss.agentos.git

import java.nio.file.Path
import java.time.Duration

/**
 * One git command to execute, described entirely by trusted server-side data.
 *
 * [gitDir] and [workTree] are the security-relevant fields: when they are set the runner passes
 * `--git-dir` / `--work-tree` explicitly, so git never discovers its repository by walking up
 * from the working directory and reading the `.git` pointer file. That pointer file is an
 * ordinary file inside a Case Exchange, writable by an agent shell, and a rewritten pointer
 * otherwise makes a command silently operate on a different case's worktree.
 *
 * Callers must build these paths from persisted metadata (the binding's recorded location),
 * never from a path supplied by a client or read out of the worktree itself.
 */
data class GitInvocation(
    /** Arguments after the hardening options, e.g. `listOf("status", "--porcelain")`. */
    val args: List<String>,
    /** Trusted `--git-dir`. Omit only for repository-less commands such as `clone` or `--version`. */
    val gitDir: Path? = null,
    /** Trusted `--work-tree`. Set together with [gitDir] for commands touching working files. */
    val workTree: Path? = null,
    /** Process working directory. Used by `clone`, and as a fallback anchor otherwise. */
    val workingDirectory: Path? = null,
    /** Overrides [GitExecutionProperties.defaultTimeout] — use the clone timeout for network calls. */
    val timeout: Duration? = null,
    /** Credentials for this call only. */
    val credentials: GitCredentials = GitCredentials.None,
)
