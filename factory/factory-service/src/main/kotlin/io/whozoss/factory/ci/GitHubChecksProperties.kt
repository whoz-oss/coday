package io.whozoss.factory.ci

import org.springframework.boot.context.properties.ConfigurationProperties

/**
 * Configuration of the deterministic GitHub PR checks step (`github-pr-checks`), bound from `factory.ci.github.*`.
 *
 * The [token] is a secret (`FACTORY_GITHUB_TOKEN`): it is only ever sent as a bearer credential to [apiBaseUrl]
 * and is never logged, persisted, or returned in evidence.
 */
@ConfigurationProperties(prefix = "factory.ci.github")
data class GitHubChecksProperties(
    val token: String = "",
    val apiBaseUrl: String = "https://api.github.com",
    /** Optional `owner/repo` fallback when the worktree has no GitHub `origin` remote. */
    val repository: String = "",
    val requestTimeoutMs: Long = 10_000L,
    /** Size of the log tail kept per failed job. */
    val logExcerptBytes: Int = 16_384,
    /** Maximum number of failed jobs whose log tail is fetched. */
    val maxFailedJobsWithLogs: Int = 5,
) {
    val configured: Boolean get() = token.isNotBlank()
}
