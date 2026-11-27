package io.whozoss.factory.ci

import com.sun.net.httpserver.HttpExchange
import com.sun.net.httpserver.HttpServer
import java.net.InetSocketAddress
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test

/** The GitHub client against local servers: token confinement, rate limits, bounded log download. */
class GitHubChecksClientTest {

    private class Recorded(val path: String, val authorization: String?)

    private lateinit var api: HttpServer
    private lateinit var other: HttpServer
    private val apiRoutes = HashMap<String, (HttpExchange) -> Unit>()
    private val otherRoutes = HashMap<String, (HttpExchange) -> Unit>()
    private val apiRequests = mutableListOf<Recorded>()
    private val otherRequests = mutableListOf<Recorded>()

    private fun start(routes: Map<String, (HttpExchange) -> Unit>, log: MutableList<Recorded>): HttpServer {
        val server = HttpServer.create(InetSocketAddress("127.0.0.1", 0), 0)
        server.createContext("/") { exchange ->
            log += Recorded(exchange.requestURI.path, exchange.requestHeaders.getFirst("Authorization"))
            val handler = routes[exchange.requestURI.path]
            if (handler == null) reply(exchange, 404, "{}") else handler(exchange)
        }
        server.start()
        return server
    }

    @BeforeEach
    fun startServers() {
        api = start(apiRoutes, apiRequests)
        other = start(otherRoutes, otherRequests)
    }

    @AfterEach
    fun stopServers() {
        api.stop(0)
        other.stop(0)
    }

    private fun reply(exchange: HttpExchange, status: Int, body: String, headers: Map<String, String> = emptyMap()) {
        val bytes = body.toByteArray()
        headers.forEach { (k, v) -> exchange.responseHeaders.add(k, v) }
        exchange.sendResponseHeaders(status, if (bytes.isEmpty()) -1 else bytes.size.toLong())
        if (bytes.isNotEmpty()) exchange.responseBody.use { it.write(bytes) } else exchange.close()
    }

    private fun client(maxLogDownloadBytes: Long = 20L * 1024 * 1024) = GitHubChecksClient(
        GitHubChecksProperties(
            token = "secret-token", apiBaseUrl = "http://127.0.0.1:${api.address.port}",
            requestTimeoutMs = 5_000, maxLogDownloadBytes = maxLogDownloadBytes,
        ),
    )

    private fun checkRunsRoute(link: String?) {
        apiRoutes["/repos/acme/widgets/commits/abc/check-runs"] = {
            reply(it, 200, """{"check_runs":[{"name":"a"}]}""", if (link == null) emptyMap() else mapOf("Link" to "<$link>; rel=\"next\""))
        }
    }

    @Test
    fun `a Link header pointing to another port is not followed and never receives the token`() {
        checkRunsRoute("http://127.0.0.1:${other.address.port}/steal?token=1")
        otherRoutes["/steal"] = { reply(it, 200, """{"check_runs":[]}""") }

        val result = client().checkRuns("acme", "widgets", "abc")

        assertThat(result).isInstanceOf(GitHubResult.Refused::class.java)
        assertThat((result as GitHubResult.Refused).message).doesNotContain("token").doesNotContain("steal")
        assertThat(otherRequests).isEmpty()
    }

    @Test
    fun `a Link header pointing to another host is not followed`() {
        checkRunsRoute("https://evil.example.com/repos/acme/widgets/commits/abc/check-runs?page=2")

        val result = client().checkRuns("acme", "widgets", "abc")

        assertThat(result).isInstanceOf(GitHubResult.Refused::class.java)
    }

    @Test
    fun `a same origin Link header is followed with the token`() {
        var calls = 0
        apiRoutes["/repos/acme/widgets/commits/abc/check-runs"] = {
            calls++
            val next = "http://127.0.0.1:${api.address.port}/repos/acme/widgets/commits/abc/check-runs?page=2"
            if (it.requestURI.query?.contains("page=2") == true) reply(it, 200, """{"check_runs":[{"name":"b"}]}""")
            else reply(it, 200, """{"check_runs":[{"name":"a"}]}""", mapOf("Link" to "<$next>; rel=\"next\""))
        }

        val result = client().checkRuns("acme", "widgets", "abc") as GitHubResult.Ok

        assertThat(result.value.map { it.path("name").asText() }).containsExactly("a", "b")
        assertThat(calls).isEqualTo(2)
        assertThat(apiRequests.map { it.authorization }.distinct()).containsExactly("Bearer secret-token")
    }

    @Test
    fun `the log redirect target never receives the token`() {
        apiRoutes["/repos/acme/widgets/actions/jobs/9/logs"] = {
            reply(it, 302, "", mapOf("Location" to "http://127.0.0.1:${other.address.port}/archive/9"))
        }
        otherRoutes["/archive/9"] = { reply(it, 200, "line one\nline two\n") }

        val result = client().jobLogTail("acme", "widgets", 9, 1024) as GitHubResult.Ok

        assertThat(result.value.text).isEqualTo("line one\nline two\n")
        assertThat(otherRequests).hasSize(1)
        assertThat(otherRequests.single().authorization).isNull()
    }

    @Test
    fun `a 5 MiB log is bounded, cut at a line boundary and leaves no partial secret`() {
        val max = 16_384
        val secretLine = "GITHUB_TOKEN=supersecretvalue\n"
        // The cut falls inside the secret line: only its last 11 bytes (`ecretvalue` + newline) survive the ring.
        val keptOfSecret = 11
        val tailFiller = "y\n".repeat((max - keptOfSecret) / 2) + "y"
        val log = "x\n".repeat(2_621_440) + secretLine + tailFiller
        apiRoutes["/repos/acme/widgets/actions/jobs/7/logs"] = { reply(it, 200, log) }

        val tail = (client().jobLogTail("acme", "widgets", 7, max) as GitHubResult.Ok).value

        assertThat(tail.truncated).isTrue()
        assertThat(tail.text.toByteArray().size).isLessThanOrEqualTo(max)
        assertThat(tail.text).doesNotContain("ecretvalue").doesNotContain("GITHUB_TOKEN")
        assertThat(tail.text).startsWith("y\n")
    }

    @Test
    fun `the download stops after the configured cap`() {
        apiRoutes["/repos/acme/widgets/actions/jobs/8/logs"] = { reply(it, 200, "z\n".repeat(100_000)) }

        val tail = (client(maxLogDownloadBytes = 50_000).jobLogTail("acme", "widgets", 8, 1024) as GitHubResult.Ok).value

        assertThat(tail.truncated).isTrue()
        assertThat(tail.text.toByteArray().size).isLessThanOrEqualTo(1024)
    }

    @Test
    fun `a rate limited 403 is transient with the delay GitHub asked for`() {
        val reset = System.currentTimeMillis() / 1000 + 120
        apiRoutes["/repos/acme/widgets/pulls"] = {
            reply(it, 403, """{"message":"API rate limit exceeded"}""", mapOf("x-ratelimit-remaining" to "0", "x-ratelimit-reset" to reset.toString()))
        }

        val result = client().findOpenPullRequest("acme", "widgets", "feat") as GitHubResult.Transient

        assertThat(result.retryAfterSeconds).isBetween(100, 125)
    }

    @Test
    fun `a secondary rate limit 403 is transient and a plain 403 is refused`() {
        apiRoutes["/repos/acme/widgets/pulls"] = { reply(it, 403, """{"message":"You have exceeded a secondary rate limit."}""") }
        assertThat(client().findOpenPullRequest("acme", "widgets", "feat")).isInstanceOf(GitHubResult.Transient::class.java)

        apiRoutes["/repos/acme/widgets/pulls"] = { reply(it, 403, """{"message":"Resource not accessible by integration"}""") }
        assertThat(client().findOpenPullRequest("acme", "widgets", "feat")).isInstanceOf(GitHubResult.Refused::class.java)
    }

    @Test
    fun `Retry-After on a 429 is honoured`() {
        apiRoutes["/repos/acme/widgets/pulls"] = { reply(it, 429, "{}", mapOf("Retry-After" to "90")) }

        val result = client().findOpenPullRequest("acme", "widgets", "feat") as GitHubResult.Transient

        assertThat(result.retryAfterSeconds).isEqualTo(90)
    }

    @Test
    fun `the properties never print the token`() {
        val properties = GitHubChecksProperties(token = "ghs_veryverysecret", apiBaseUrl = "https://api.github.com")

        assertThat(properties.toString()).doesNotContain("ghs_veryverysecret").contains("<redacted>")
        assertThat(GitHubChecksProperties().toString()).contains("<unset>")
    }

    @Test
    fun `a blank token falls back to the gh cli token, resolved once and never printed`() {
        var calls = 0
        val properties = GitHubChecksProperties(token = "", ghCliFallback = true).useGhTokenSupplier {
            calls++
            "gho_fromghcli\n"
        }
        assertThat(properties.toString()).contains("<unset>")
        assertThat(calls).isZero()

        assertThat(properties.configured).isTrue()
        assertThat(properties.effectiveToken).isEqualTo("gho_fromghcli")
        assertThat(properties.effectiveToken).isEqualTo("gho_fromghcli")

        assertThat(calls).isEqualTo(1)
        assertThat(properties.toString()).doesNotContain("gho_fromghcli").contains("<redacted>")
    }

    @Test
    fun `the configured token wins and gh is not consulted`() {
        var calls = 0
        val properties = GitHubChecksProperties(token = "configured", ghCliFallback = true).useGhTokenSupplier { calls++; "fromgh" }

        assertThat(properties.effectiveToken).isEqualTo("configured")
        assertThat(calls).isZero()
    }

    @Test
    fun `gh is not consulted when the fallback is off, and a blank answer is retried later`() {
        var calls = 0
        val off = GitHubChecksProperties(token = "", ghCliFallback = false).useGhTokenSupplier { calls++; "fromgh" }
        assertThat(off.configured).isFalse()
        assertThat(calls).isZero()

        var answer = ""
        val on = GitHubChecksProperties(token = "", ghCliFallback = true).useGhTokenSupplier { calls++; answer }
        assertThat(on.configured).isFalse()
        answer = "loggedin"
        assertThat(on.effectiveToken).isEqualTo("loggedin")
        assertThat(calls).isEqualTo(2)
    }

    @Test
    fun `a failing gh command leaves the token unset`() {
        val properties = GitHubChecksProperties(token = "", ghCliFallback = true).useGhTokenSupplier { error("gh missing") }

        assertThat(properties.configured).isFalse()
    }

    @Test
    fun `the admin token wins over the configured token and the gh cli, and says where it comes from`() {
        var calls = 0
        val properties = GitHubChecksProperties(token = "configured", ghCliFallback = true).useGhTokenSupplier { calls++; "fromgh" }
        assertThat(properties.tokenSource).isEqualTo("environment")

        properties.adminToken = "from-admin"
        assertThat(properties.effectiveToken).isEqualTo("from-admin")
        assertThat(properties.tokenSource).isEqualTo("admin")
        assertThat(properties.toString()).doesNotContain("from-admin").contains("<redacted>")

        properties.adminToken = "  "
        assertThat(properties.tokenSource).isEqualTo("environment")
        assertThat(properties.effectiveToken).isEqualTo("configured")

        val ghOnly = GitHubChecksProperties(token = "", ghCliFallback = true).useGhTokenSupplier { "fromgh" }
        assertThat(ghOnly.tokenSource).isEqualTo("gh-cli")
        assertThat(GitHubChecksProperties().tokenSource).isEqualTo("none")
        assertThat(calls).isZero()
    }

    @Test
    fun `the masked hint never reveals a whole token`() {
        val properties = GitHubChecksProperties()

        assertThat(properties.maskedHint("ghp_abcdefghijklmnopqrstuvwxyz0123456789")).isEqualTo("ghp_…6789")
        assertThat(properties.maskedHint("short")).isEqualTo("****")
        assertThat(properties.maskedHint("12345678")).isEqualTo("****")
        assertThat(properties.maskedHint("")).isEqualTo("****")
    }

    @Test
    fun `verifyToken reports the login and scopes of a token GitHub accepts`() {
        apiRoutes["/user"] = { reply(it, 200, """{"login":"octocat"}""", mapOf("X-OAuth-Scopes" to "repo, read:org")) }

        val result = client().verifyToken("ghp_candidate") as GitHubTokenVerification.Connected

        assertThat(result.login).isEqualTo("octocat")
        assertThat(result.scopes).isEqualTo("repo, read:org")
        assertThat(apiRequests.map { it.authorization }).containsExactly("Bearer ghp_candidate")
    }

    @Test
    fun `verifyToken without an override checks the effective token`() {
        apiRoutes["/user"] = { reply(it, 200, """{"login":"octocat"}""") }

        assertThat(client().verifyToken()).isInstanceOf(GitHubTokenVerification.Connected::class.java)
        assertThat(apiRequests.map { it.authorization }).containsExactly("Bearer secret-token")
    }

    @Test
    fun `verifyToken says invalid on 401 and on a plain 403 that rate_limit also refuses`() {
        apiRoutes["/user"] = { reply(it, 401, """{"message":"Bad credentials"}""") }
        val unauthorized = client().verifyToken("bad") as GitHubTokenVerification.Invalid
        assertThat(unauthorized.message).isEqualTo("GitHub rejected this token (HTTP 401).")

        apiRoutes["/user"] = { reply(it, 403, """{"message":"Forbidden"}""") }
        apiRoutes["/rate_limit"] = { reply(it, 403, """{"message":"Forbidden"}""") }
        val forbidden = client().verifyToken("bad") as GitHubTokenVerification.Invalid
        assertThat(forbidden.message).isEqualTo("GitHub rejected this token (HTTP 403).")
    }

    @Test
    fun `verifyToken accepts an installation token that cannot read the user but can read rate_limit`() {
        apiRoutes["/user"] = { reply(it, 403, """{"message":"Resource not accessible by integration"}""") }
        apiRoutes["/rate_limit"] = { reply(it, 200, """{"resources":{}}""") }

        val result = client().verifyToken("ghs_installation") as GitHubTokenVerification.Connected

        assertThat(result.login).isNull()
    }

    @Test
    fun `verifyToken says unreachable on a server error, a rate limit and a network failure`() {
        apiRoutes["/user"] = { reply(it, 503, "{}") }
        assertThat(client().verifyToken("t")).isInstanceOf(GitHubTokenVerification.Unreachable::class.java)

        apiRoutes["/user"] = { reply(it, 403, """{"message":"API rate limit exceeded"}""", mapOf("x-ratelimit-remaining" to "0")) }
        val limited = client().verifyToken("t") as GitHubTokenVerification.Unreachable
        assertThat(limited.message).startsWith("Could not reach GitHub (")

        api.stop(0)
        assertThat(client().verifyToken("t")).isInstanceOf(GitHubTokenVerification.Unreachable::class.java)
    }

    @Test
    fun `verifyToken of a blank token is invalid without any request`() {
        val result = client().verifyToken("   ")

        assertThat(result).isInstanceOf(GitHubTokenVerification.Invalid::class.java)
        assertThat(apiRequests).isEmpty()
    }

    @Test
    fun `the client sends the gh cli token as the bearer credential`() {
        apiRoutes["/repos/acme/widgets/pulls"] = { reply(it, 200, "[]") }
        val properties = GitHubChecksProperties(token = "", ghCliFallback = true, apiBaseUrl = "http://127.0.0.1:${api.address.port}", requestTimeoutMs = 5_000)
            .useGhTokenSupplier { "gho_fromghcli" }

        GitHubChecksClient(properties).findOpenPullRequest("acme", "widgets", "feat")

        assertThat(apiRequests.map { it.authorization }.distinct()).containsExactly("Bearer gho_fromghcli")
    }
}
