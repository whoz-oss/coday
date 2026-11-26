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
}
