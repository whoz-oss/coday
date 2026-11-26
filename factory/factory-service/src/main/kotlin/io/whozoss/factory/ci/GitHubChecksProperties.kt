package io.whozoss.factory.ci

import org.springframework.boot.context.properties.ConfigurationProperties

/**
 * Configuration of the deterministic GitHub PR checks step (`github-pr-checks`), bound from `factory.ci.github.*`.
 *
 * The [token] is a secret (`FACTORY_GITHUB_TOKEN`): it is only ever sent as a bearer credential to [apiBaseUrl]
 * and is never logged, persisted, or returned in evidence. This is deliberately NOT a data class: a generated
 * `toString()` would print the token, so [toString] is explicit and redacts it.
 */
@ConfigurationProperties(prefix = "factory.ci.github")
class GitHubChecksProperties(
    val token: String = "",
    val apiBaseUrl: String = "https://api.github.com",
    /**
     * Optional `owner/repo`. Without [allowedRepositories] it is both the fallback when the worktree has no GitHub
     * `origin` remote and the only repository a step may query.
     */
    val repository: String = "",
    /**
     * `owner/repo` entries (case-insensitive) the token may be used for. When non-empty, a repository resolved from
     * the worktree `origin` that is not listed is refused (`CI_REPOSITORY_NOT_ALLOWED`). Empty keeps the
     * unrestricted behaviour unless [repository] is set.
     */
    val allowedRepositories: List<String> = emptyList(),
    val requestTimeoutMs: Long = 10_000L,
    /** Size of the log tail kept per failed job. */
    val logExcerptBytes: Int = 16_384,
    /** Maximum number of failed jobs whose log tail is fetched. */
    val maxFailedJobsWithLogs: Int = 5,
    /** At most this many bytes of one job log are read from the network (default 20 MiB). */
    val maxLogDownloadBytes: Long = 20L * 1024 * 1024,
    /** Total GitHub time one poll may spend fetching failure logs; once spent, remaining checks keep no log. */
    val pollBudgetMs: Long = 30_000L,
) {
    val configured: Boolean get() = token.isNotBlank()

    /** The repositories a step may query, or empty when unrestricted. */
    val effectiveAllowedRepositories: List<String>
        get() = when {
            allowedRepositories.isNotEmpty() -> allowedRepositories
            repository.isNotBlank() -> listOf(repository)
            else -> emptyList()
        }

    override fun toString(): String =
        "GitHubChecksProperties(token=${if (token.isBlank()) "<unset>" else "<redacted>"}, apiBaseUrl=$apiBaseUrl, " +
            "repository=$repository, allowedRepositories=$allowedRepositories, requestTimeoutMs=$requestTimeoutMs, " +
            "logExcerptBytes=$logExcerptBytes, maxFailedJobsWithLogs=$maxFailedJobsWithLogs, " +
            "maxLogDownloadBytes=$maxLogDownloadBytes, pollBudgetMs=$pollBudgetMs)"
}
