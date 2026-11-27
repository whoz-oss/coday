package io.whozoss.factory.ci

import java.util.concurrent.TimeUnit
import org.springframework.boot.context.properties.ConfigurationProperties

/**
 * Configuration of the deterministic GitHub PR checks step (`github-pr-checks`), bound from `factory.ci.github.*`.
 *
 * The GitHub credential is a secret: it is only ever sent as a bearer credential to [apiBaseUrl] and is never
 * logged, persisted, or returned in evidence. This is deliberately NOT a data class: a generated `toString()`
 * would print the token, so [toString] is explicit and redacts it.
 *
 * Token resolution ([effectiveToken]), first non blank wins:
 *  1. [adminToken], set at runtime from the cockpit Admin page (persisted and encrypted by `GitHubTokenSettingsService`,
 *     which loads it at startup and keeps this memory copy up to date, so a poll never reads the database);
 *  2. [token], bound from `FACTORY_GITHUB_TOKEN`, then `GITHUB_TOKEN`, then `GH_TOKEN` (see `application.yml`);
 *  3. when [ghCliFallback] is true, the output of `gh auth token` (the developer's local GitHub CLI login).
 *
 * [ghCliFallback] defaults to `false` here so that code (and unit tests) that build this class by hand never pick up
 * the real `gh` login of the host; `application.yml` turns it on for the running service.
 *
 * The primary constructor only holds bindable properties, so Spring's constructor binding is unambiguous: the
 * `gh` command runner is not a constructor parameter, tests replace it with [useGhTokenSupplier].
 */
@ConfigurationProperties(prefix = "factory.ci.github")
class GitHubChecksProperties(
    val token: String = "",
    /** Fall back to `gh auth token` when [token] is blank (`factory.ci.github.gh-cli-fallback`). */
    val ghCliFallback: Boolean = false,
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
    private var ghTokenSupplier: () -> String = { runGhAuthToken() }

    /** A non blank `gh auth token` result, kept for the life of this instance: `gh` is not spawned on every call. */
    @Volatile
    private var cachedGhToken: String? = null

    /** Token configured from the Admin page; wins over every other source. Never log it, never return it in a DTO. */
    @Volatile
    var adminToken: String? = null

    /**
     * The token to send to GitHub: [adminToken], else [token], else (when [ghCliFallback]) the cached
     * `gh auth token`, else blank.
     */
    val effectiveToken: String
        get() {
            adminToken?.takeIf { it.isNotBlank() }?.let { return it }
            if (token.isNotBlank()) return token
            if (!ghCliFallback) return ""
            cachedGhToken?.let { return it }
            return synchronized(this) {
                cachedGhToken ?: runCatching { ghTokenSupplier().trim() }.getOrDefault("")
                    .also { if (it.isNotBlank()) cachedGhToken = it }
            }
        }

    val configured: Boolean get() = effectiveToken.isNotBlank()

    /** Where [effectiveToken] comes from: `admin`, `environment`, `gh-cli` or `none`. */
    val tokenSource: String
        get() = when {
            !adminToken.isNullOrBlank() -> SOURCE_ADMIN
            token.isNotBlank() -> SOURCE_ENVIRONMENT
            effectiveToken.isNotBlank() -> SOURCE_GH_CLI
            else -> SOURCE_NONE
        }

    /**
     * A hint that identifies a token without revealing it: its first and last 4 characters when it is long enough
     * (real GitHub tokens are 40+ characters), otherwise `****`.
     */
    fun maskedHint(raw: String): String =
        if (raw.length >= MASK_MIN_LENGTH) "${raw.take(MASK_EDGE)}…${raw.takeLast(MASK_EDGE)}" else "****"

    /** Replaces the `gh auth token` command runner (tests only): keeps them independent from the host's `gh`. */
    internal fun useGhTokenSupplier(supplier: () -> String): GitHubChecksProperties = apply {
        ghTokenSupplier = supplier
        cachedGhToken = null
    }

    /** The repositories a step may query, or empty when unrestricted. */
    val effectiveAllowedRepositories: List<String>
        get() = when {
            allowedRepositories.isNotEmpty() -> allowedRepositories
            repository.isNotBlank() -> listOf(repository)
            else -> emptyList()
        }

    /** Never spawns `gh` and never prints a token: only says whether one is known right now. */
    override fun toString(): String =
        "GitHubChecksProperties(token=${if (token.isNotBlank() || cachedGhToken != null || !adminToken.isNullOrBlank()) "<redacted>" else "<unset>"}, " +
            "ghCliFallback=$ghCliFallback, apiBaseUrl=$apiBaseUrl, " +
            "repository=$repository, allowedRepositories=$allowedRepositories, requestTimeoutMs=$requestTimeoutMs, " +
            "logExcerptBytes=$logExcerptBytes, maxFailedJobsWithLogs=$maxFailedJobsWithLogs, " +
            "maxLogDownloadBytes=$maxLogDownloadBytes, pollBudgetMs=$pollBudgetMs)"

    companion object {
        const val SOURCE_ADMIN = "admin"
        const val SOURCE_ENVIRONMENT = "environment"
        const val SOURCE_GH_CLI = "gh-cli"
        const val SOURCE_NONE = "none"
        private const val MASK_MIN_LENGTH = 16
        private const val MASK_EDGE = 4
        private const val GH_TIMEOUT_SECONDS = 3L

        /**
         * `gh auth token`, bounded by a short timeout. Returns blank when `gh` is missing, not logged in, too slow or
         * fails: the output (stderr is merged into stdout) is only trusted on a zero exit code, and is never logged.
         */
        private fun runGhAuthToken(): String {
            val process = try {
                ProcessBuilder("gh", "auth", "token").redirectErrorStream(true).start()
            } catch (ignored: Exception) {
                return ""
            }
            return try {
                process.outputStream.close()
                if (!process.waitFor(GH_TIMEOUT_SECONDS, TimeUnit.SECONDS)) {
                    process.destroyForcibly()
                    ""
                } else if (process.exitValue() != 0) {
                    ""
                } else {
                    process.inputStream.bufferedReader().use { it.readText() }.lineSequence().firstOrNull().orEmpty().trim()
                }
            } catch (interrupted: InterruptedException) {
                Thread.currentThread().interrupt()
                process.destroyForcibly()
                ""
            } catch (ignored: Exception) {
                process.destroyForcibly()
                ""
            }
        }
    }
}
