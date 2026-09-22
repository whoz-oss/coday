package io.whozoss.agentos.git

import org.springframework.boot.context.properties.ConfigurationProperties
import java.time.Duration

/**
 * Configuration properties for server-side git execution.
 *
 * Bound from the `agentos.git` prefix in application.yml.
 *
 * Example (application.yml):
 * ```yaml
 * agentos:
 *   git:
 *     binary: git
 *     default-timeout: 2m
 *     clone-timeout: 30m
 * ```
 *
 * Override with environment variables (Spring Boot relaxed binding):
 * - AGENTOS_GIT_BINARY
 * - AGENTOS_GIT_DEFAULT_TIMEOUT
 * - AGENTOS_GIT_CLONE_TIMEOUT
 * - AGENTOS_GIT_MAX_OUTPUT_CHARS
 * - AGENTOS_GIT_ALLOW_PRIVATE_REMOTE_HOSTS
 *
 * | Property | Env var | Default | Purpose |
 * | --- | --- | --- | --- |
 * | `binary` | `AGENTOS_GIT_BINARY` | `git` | Absolute path or PATH-resolved name of the git executable |
 * | `defaultTimeout` | `AGENTOS_GIT_DEFAULT_TIMEOUT` | `2m` | Timeout for ordinary plumbing commands |
 * | `cloneTimeout` | `AGENTOS_GIT_CLONE_TIMEOUT` | `30m` | Timeout for clone/fetch of a large repository |
 * | `maxOutputChars` | `AGENTOS_GIT_MAX_OUTPUT_CHARS` | `100000` | Cap on captured stdout/stderr |
 * | `allowPrivateRemoteHosts` | `AGENTOS_GIT_ALLOW_PRIVATE_REMOTE_HOSTS` | `false` | Permit RFC1918/loopback remotes (self-hosted forge) |
 */
@ConfigurationProperties(prefix = "agentos.git")
data class GitExecutionProperties(
    /**
     * The git executable. A bare name is resolved through `PATH`; prefer an absolute path in
     * hardened deployments so the binary cannot be shadowed.
     *
     * The runtime image includes Git — see `agentos/docs/git-workspaces.md`
     * for the deployment prerequisites.
     */
    val binary: String = "git",
    /** Timeout applied to ordinary commands (status, rev-parse, worktree add, ...). */
    val defaultTimeout: Duration = Duration.ofMinutes(2),
    /** Timeout applied to network-bound commands (clone, fetch). */
    val cloneTimeout: Duration = Duration.ofMinutes(30),
    /** Timeout applied to the configured setup command run inside a new worktree. */
    val setupTimeout: Duration = Duration.ofMinutes(15),
    /** Upper bound on captured stdout/stderr characters, to keep a runaway command out of the heap. */
    val maxOutputChars: Int = 100_000,
    /**
     * Transport protocols git is allowed to use. Everything else is denied through
     * `protocol.allow=never`, which also neutralises `ext::` command execution and any
     * `url.<base>.insteadOf` rewrite pointing at an exotic transport.
     *
     * Production keeps the `https` default. Tests add `file` so they can exercise the runner
     * against a local throwaway repository.
     */
    val allowedRemoteProtocols: Set<String> = setOf("https"),
    /**
     * Whether a remote URL may resolve to a loopback, link-local or RFC1918 host.
     *
     * `false` (default) blocks SSRF-style targets. Set to `true` only for a deployment whose
     * forge genuinely lives on a private network, which is the common case for a self-hosted
     * GitLab reachable from the AgentOS instance.
     */
    val allowPrivateRemoteHosts: Boolean = false,
)
