package io.whozoss.factory.ci

import com.sun.net.httpserver.HttpExchange
import com.sun.net.httpserver.HttpServer
import io.whozoss.factory.capability.CapabilityOutcome
import java.net.InetSocketAddress
import java.nio.file.Files
import java.nio.file.Path
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir

/** Deterministic CI check against a local HTTP server standing in for api.github.com: no LLM, no process, no network. */
class PullRequestChecksCapabilityTest {

    @TempDir lateinit var repo: Path

    private lateinit var server: HttpServer
    private val sha = "0123456789abcdef0123456789abcdef01234567"
    private val routes = HashMap<String, (HttpExchange) -> Unit>()
    private val requests = mutableListOf<Pair<String, String?>>()

    @BeforeEach
    fun startServer() {
        server = HttpServer.create(InetSocketAddress("127.0.0.1", 0), 0)
        server.createContext("/") { exchange ->
            requests += exchange.requestURI.path to exchange.requestHeaders.getFirst("Authorization")
            val handler = routes[exchange.requestURI.path]
            if (handler == null) reply(exchange, 404, "{}") else handler(exchange)
        }
        server.start()
    }

    @AfterEach
    fun stopServer() = server.stop(0)

    private fun reply(exchange: HttpExchange, status: Int, body: String, headers: Map<String, String> = emptyMap()) {
        val bytes = body.toByteArray()
        headers.forEach { (k, v) -> exchange.responseHeaders.add(k, v) }
        exchange.responseHeaders.add("Content-Type", "application/json")
        exchange.sendResponseHeaders(status, bytes.size.toLong())
        exchange.responseBody.use { it.write(bytes) }
    }

    private fun capability(token: String = "secret-token", repository: String = "") = PullRequestChecksCapability(
        GitHubChecksProperties(token = token, apiBaseUrl = "http://127.0.0.1:${server.address.port}", repository = repository, requestTimeoutMs = 3_000),
        GitHubChecksClient(GitHubChecksProperties(token = token, apiBaseUrl = "http://127.0.0.1:${server.address.port}", requestTimeoutMs = 3_000)),
    )

    private fun git(origin: String? = "https://github.com/acme/widgets.git", branch: String? = "feat/ci", head: String? = sha) {
        val dir = Files.createDirectories(repo.resolve(".git"))
        Files.deleteIfExists(dir.resolve("config"))
        if (origin != null) Files.writeString(dir.resolve("config"), "[core]\n\tbare = false\n[remote \"origin\"]\n\turl = $origin\n")
        Files.writeString(dir.resolve("HEAD"), if (branch != null) "ref: refs/heads/$branch\n" else "$sha\n")
        if (branch != null && head != null) {
            Files.createDirectories(dir.resolve("refs/heads/${branch.substringBeforeLast('/', "")}"))
            Files.writeString(dir.resolve("refs/heads/$branch"), "$head\n")
        }
    }

    private fun pull(draft: Boolean = false, headSha: String = sha, branch: String = "feat/ci") =
        """[{"number":7,"html_url":"https://github.com/acme/widgets/pull/7","draft":$draft,"head":{"ref":"$branch","sha":"$headSha"}}]"""

    private fun run(id: Long, name: String, status: String, conclusion: String?, app: String = "github-actions") =
        """{"id":$id,"name":"$name","status":"$status","conclusion":${conclusion?.let { "\"$it\"" }},"html_url":"https://ci/$id","app":{"slug":"$app"},"output":{"summary":"summary of $name"}}"""

    private fun serve(pull: String = pull(), runs: List<String> = emptyList(), statuses: String = "[]") {
        routes["/repos/acme/widgets/pulls"] = { reply(it, 200, pull) }
        routes["/repos/acme/widgets/commits/$sha/check-runs"] = { reply(it, 200, """{"total_count":${runs.size},"check_runs":[${runs.joinToString(",")}]}""") }
        routes["/repos/acme/widgets/commits/$sha/status"] = { reply(it, 200, """{"state":"success","statuses":$statuses}""") }
    }

    private fun check() = capability().check("ci", repo)

    private fun pendingReason(outcome: CapabilityOutcome) = (outcome as CapabilityOutcome.ExternalPending).reason
    private fun refusedCode(outcome: CapabilityOutcome) = (outcome as CapabilityOutcome.CodeRefused).code

    @Test
    fun `all checks green passes and binds the verdict to the PR head`() {
        git(); serve(runs = listOf(run(1, "validate-ts", "completed", "success"), run(2, "validate-jvm", "completed", "skipped"), run(3, "merge-gate", "completed", "neutral")))
        val outcome = check() as CapabilityOutcome.CiChecksCompleted
        assertThat(outcome.passed).isTrue()
        assertThat(outcome.prNumber).isEqualTo(7)
        assertThat(outcome.headSha).isEqualTo(sha)
        assertThat(outcome.checks).hasSize(3)
        assertThat(outcome.failedChecks).isEmpty()
    }

    @Test
    fun `a running check or no check at all is pending`() {
        git(); serve(runs = listOf(run(1, "validate-ts", "completed", "success"), run(2, "validate-jvm", "in_progress", null)))
        assertThat(pendingReason(check())).isEqualTo("CI_CHECKS_PENDING")
        serve(runs = emptyList())
        assertThat(pendingReason(check())).isEqualTo("CI_CHECKS_PENDING")
    }

    @Test
    fun `a pending commit status is pending`() {
        git(); serve(runs = listOf(run(1, "a", "completed", "success")), statuses = """[{"context":"legacy","state":"pending","target_url":"","description":""}]""")
        assertThat(pendingReason(check())).isEqualTo("CI_CHECKS_PENDING")
    }

    @Test
    fun `a local head that differs from the PR head is pending and never a stale green`() {
        git(head = "f".repeat(40)); serve(runs = listOf(run(1, "a", "completed", "success")))
        assertThat(pendingReason(check())).isEqualTo("CI_HEAD_NOT_PUSHED")
    }

    @Test
    fun `a failure returns the failed checks with a truncated redacted log tail`() {
        git()
        val log = "header-dropped\n" + "x".repeat(40_000) + "\nGITHUB_TOKEN=ghs_supersecret\nAuthorization: Bearer abc123\nFAILED SomeTest"
        routes["/repos/acme/widgets/actions/jobs/2/logs"] = { reply(it, 200, log) }
        serve(runs = listOf(run(1, "validate-ts", "completed", "success"), run(2, "validate-jvm", "completed", "failure"), run(3, "external", "completed", "timed_out", app = "circleci")))
        val outcome = check() as CapabilityOutcome.CiChecksCompleted
        assertThat(outcome.passed).isFalse()
        assertThat(outcome.failedChecks.map { it["name"] }).containsExactly("validate-jvm", "external")
        val withLog = outcome.failedChecks.first()
        assertThat(withLog["logTruncated"]).isEqualTo(true)
        val excerpt = withLog["logExcerpt"] as String
        assertThat(excerpt).contains("FAILED SomeTest").doesNotContain("header-dropped", "ghs_supersecret", "abc123")
        assertThat(excerpt.length).isLessThanOrEqualTo(16_384 + 64)
        // A non GitHub Actions failure keeps its summary but never triggers a log download.
        assertThat(outcome.failedChecks.last()).doesNotContainKey("logExcerpt")
    }

    @Test
    fun `a cancelled or stale check is not a verdict and keeps polling`() {
        git(); serve(runs = listOf(run(1, "validate-ts", "completed", "success"), run(2, "validate-jvm", "completed", "cancelled")))
        assertThat(pendingReason(check())).isEqualTo("CI_CHECKS_NOT_CONCLUSIVE")
        serve(runs = listOf(run(1, "validate-ts", "completed", "stale")))
        assertThat(pendingReason(check())).isEqualTo("CI_CHECKS_NOT_CONCLUSIVE")
        serve(runs = listOf(run(1, "validate-ts", "completed", "action_required")))
        assertThat(pendingReason(check())).isEqualTo("CI_CHECKS_NOT_CONCLUSIVE")
    }

    @Test
    fun `a cancelled run followed by a successful rerun of the same name passes`() {
        git()
        val cancelled = run(1, "validate-jvm", "completed", "cancelled").replace("\"name\"", "\"started_at\":\"2026-01-01T10:00:00Z\",\"name\"")
        val rerun = run(2, "validate-jvm", "completed", "success").replace("\"name\"", "\"started_at\":\"2026-01-01T10:05:00Z\",\"name\"")
        serve(runs = listOf(cancelled, rerun))
        val outcome = check() as CapabilityOutcome.CiChecksCompleted
        assertThat(outcome.passed).isTrue()
        assertThat(outcome.checks).hasSize(1)
    }

    @Test
    fun `a failing status deep in the status list is a red verdict`() {
        git()
        val statuses = (1..60).joinToString(",", "[", "]") { i ->
            val state = if (i == 40) "failure" else "success"
            """{"context":"ctx-$i","state":"$state","target_url":"","description":""}"""
        }
        serve(runs = listOf(run(1, "a", "completed", "success")), statuses = statuses)
        val outcome = check() as CapabilityOutcome.CiChecksCompleted
        assertThat(outcome.passed).isFalse()
        assertThat(outcome.failedChecks.single()["name"]).isEqualTo("ctx-40")
    }

    @Test
    fun `a rate limited GitHub is pending with the requested delay`() {
        git()
        val reset = System.currentTimeMillis() / 1000 + 120
        routes["/repos/acme/widgets/pulls"] = {
            reply(it, 403, """{"message":"API rate limit exceeded"}""", mapOf("x-ratelimit-remaining" to "0", "x-ratelimit-reset" to reset.toString()))
        }
        val outcome = check() as CapabilityOutcome.ExternalPending
        assertThat(outcome.reason).startsWith("CI_GITHUB_TRANSIENT")
        assertThat(outcome.retryAfterSeconds).isBetween(100, 125)

        routes["/repos/acme/widgets/pulls"] = { reply(it, 403, """{"message":"You have exceeded a secondary rate limit."}""") }
        assertThat(pendingReason(check())).startsWith("CI_GITHUB_TRANSIENT")

        routes["/repos/acme/widgets/pulls"] = { reply(it, 403, """{"message":"Forbidden"}""") }
        assertThat(refusedCode(check())).isEqualTo("CI_GITHUB_REFUSED")
    }

    @Test
    fun `a repository outside the allow list is refused before any call`() {
        git(origin = "https://github.com/evil/other.git"); serve()
        val restricted = PullRequestChecksCapability(
            GitHubChecksProperties(token = "t", apiBaseUrl = "http://127.0.0.1:${server.address.port}", allowedRepositories = listOf("Acme/Widgets")),
            GitHubChecksClient(GitHubChecksProperties(token = "t", apiBaseUrl = "http://127.0.0.1:${server.address.port}")),
        )
        assertThat(refusedCode(restricted.check("ci", repo))).isEqualTo("CI_REPOSITORY_NOT_ALLOWED")
        assertThat(requests).isEmpty()

        git(); serve(runs = listOf(run(1, "a", "completed", "success")))
        assertThat((restricted.check("ci", repo) as CapabilityOutcome.CiChecksCompleted).passed).isTrue()
    }

    @Test
    fun `a log cut in the middle of a secret keeps neither the partial secret nor more than the excerpt size`() {
        git()
        val excerptBytes = 16_384
        val secretLine = "GITHUB_TOKEN=ghp_" + "a".repeat(36) + "\n"
        // Only the last 20 bytes of the secret line fit in the excerpt: the cut lands INSIDE the token.
        val kept = 20
        val tailFiller = "y\n".repeat((excerptBytes - kept) / 2)
        val log = "x\n".repeat(2_621_440) + secretLine + tailFiller
        assertThat(log.length).isGreaterThan(5 * 1024 * 1024)
        routes["/repos/acme/widgets/actions/jobs/2/logs"] = { reply(it, 200, log) }
        serve(runs = listOf(run(2, "validate-jvm", "completed", "failure")))

        val outcome = check() as CapabilityOutcome.CiChecksCompleted

        val failed = outcome.failedChecks.single()
        val excerpt = failed["logExcerpt"] as String
        assertThat(failed["logTruncated"]).isEqualTo(true)
        assertThat(excerpt.toByteArray().size).isLessThanOrEqualTo(excerptBytes)
        assertThat(excerpt).startsWith("y\n")
        assertThat(excerpt).doesNotContain("aaaaaaaa", "GITHUB_TOKEN", "ghp_")
    }

    @Test
    fun `a failed commit status is a failure`() {
        git(); serve(runs = listOf(run(1, "a", "completed", "success")), statuses = """[{"context":"legacy","state":"failure","target_url":"https://legacy","description":"broke"}]""")
        val outcome = check() as CapabilityOutcome.CiChecksCompleted
        assertThat(outcome.passed).isFalse()
        assertThat(outcome.failedChecks.single()["name"]).isEqualTo("legacy")
    }

    @Test
    fun `a draft pull request is refused because its CI is skipped`() {
        git(); serve(pull = pull(draft = true))
        assertThat(refusedCode(check())).isEqualTo("CI_PR_IS_DRAFT")
    }

    @Test
    fun `no open pull request is refused`() {
        git(); serve(pull = "[]")
        assertThat(refusedCode(check())).isEqualTo("CI_PR_NOT_FOUND")
    }

    @Test
    fun `a missing token is refused without any call`() {
        git(); serve()
        assertThat(refusedCode(capability(token = "").check("ci", repo))).isEqualTo("CI_GITHUB_NOT_CONFIGURED")
        assertThat(requests).isEmpty()
    }

    @Test
    fun `an authentication error is refused and not retried as pending`() {
        git(); routes["/repos/acme/widgets/pulls"] = { reply(it, 401, """{"message":"Bad credentials"}""") }
        assertThat(refusedCode(check())).isEqualTo("CI_GITHUB_REFUSED")
    }

    @Test
    fun `server errors and rate limiting are treated as pending`() {
        git()
        routes["/repos/acme/widgets/pulls"] = { reply(it, 503, "{}") }
        assertThat(pendingReason(check())).startsWith("CI_GITHUB_TRANSIENT")
        routes["/repos/acme/widgets/pulls"] = { reply(it, 429, "{}") }
        assertThat(pendingReason(check())).startsWith("CI_GITHUB_TRANSIENT")
    }

    @Test
    fun `an unreachable GitHub is treated as pending`() {
        git(); serve()
        server.stop(0)
        assertThat(pendingReason(check())).startsWith("CI_GITHUB_TRANSIENT")
    }

    @Test
    fun `the repository comes from an ssh origin and falls back to the property`() {
        git(origin = "git@github.com:acme/widgets.git"); serve(runs = listOf(run(1, "a", "completed", "success")))
        assertThat((check() as CapabilityOutcome.CiChecksCompleted).passed).isTrue()

        git(origin = null); serve(runs = listOf(run(1, "a", "completed", "success")))
        assertThat(refusedCode(capability().check("ci", repo))).isEqualTo("CI_REPOSITORY_UNRESOLVED")
        assertThat((capability(repository = "acme/widgets").check("ci", repo) as CapabilityOutcome.CiChecksCompleted).passed).isTrue()
    }

    @Test
    fun `a detached head has no branch to look up`() {
        git(branch = null); serve()
        assertThat(refusedCode(check())).isEqualTo("CI_BRANCH_UNRESOLVED")
    }

    @Test
    fun `the token is only sent as a bearer credential to the API`() {
        git(); serve(runs = listOf(run(1, "a", "completed", "success")))
        check()
        assertThat(requests).isNotEmpty()
        assertThat(requests.map { it.second }.distinct()).containsExactly("Bearer secret-token")
    }
}
