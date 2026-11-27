package io.whozoss.factory.settings

import com.sun.net.httpserver.HttpExchange
import com.sun.net.httpserver.HttpServer
import io.mockk.every
import io.mockk.mockk
import io.whozoss.factory.Neo4jDomainIntegrationTest
import io.whozoss.factory.ci.GitHubChecksClient
import io.whozoss.factory.ci.GitHubChecksProperties
import java.net.InetSocketAddress
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.data.neo4j.core.Neo4jClient

/**
 * Embedded-Neo4j integration tests of [GitHubTokenSettingsService] against a local HTTP server standing in for
 * api.github.com: persistence, encryption at rest, masking, live verification and token priority.
 */
class GitHubTokenSettingsIntegrationTest : Neo4jDomainIntegrationTest() {

    @Autowired
    private lateinit var neo4jClient: Neo4jClient

    private lateinit var github: HttpServer
    private val routes = HashMap<String, (HttpExchange) -> Unit>()
    private val userCalls = mutableListOf<String?>()
    private val token = "ghp_abcdefghijklmnopqrstuvwxyz0123456789"

    @BeforeEach
    fun startGitHub() {
        github = HttpServer.create(InetSocketAddress("127.0.0.1", 0), 0)
        github.createContext("/") { exchange ->
            if (exchange.requestURI.path == "/user") userCalls += exchange.requestHeaders.getFirst("Authorization")
            val handler = routes[exchange.requestURI.path]
            if (handler == null) reply(exchange, 404, "{}") else handler(exchange)
        }
        github.start()
        routes["/user"] = { reply(it, 200, """{"login":"octocat"}""", mapOf("X-OAuth-Scopes" to "repo")) }
    }

    @AfterEach
    fun stopGitHub() = github.stop(0)

    private fun reply(exchange: HttpExchange, status: Int, body: String, headers: Map<String, String> = emptyMap()) {
        val bytes = body.toByteArray()
        headers.forEach { (k, v) -> exchange.responseHeaders.add(k, v) }
        exchange.sendResponseHeaders(status, bytes.size.toLong())
        exchange.responseBody.use { it.write(bytes) }
    }

    private fun newProperties(environmentToken: String = "") = GitHubChecksProperties(
        token = environmentToken, apiBaseUrl = "http://127.0.0.1:${github.address.port}", requestTimeoutMs = 3_000,
    )

    /** A fresh service instance, like a Factory restart when called again on the same graph. */
    private fun newService(
        props: GitHubChecksProperties = newProperties(),
        key: String = "NONE",
        salt: String = "NONE",
    ) = GitHubTokenSettingsService(neo4jClient, props, GitHubChecksClient(props), SecretCipher(key, salt))

    private fun storedValue(): String? =
        neo4jClient.query("MATCH (s:FactorySetting {id: 'github.token'}) RETURN s.stringValue AS value")
            .fetch().one().orElse(null)?.get("value") as? String

    @Test
    fun `a valid token is verified live, saved, masked and made the effective token`() {
        val props = newProperties()
        val subject = newService(props)

        val result = subject.save("  $token ", "alice")

        assertThat(result.status).isEqualTo("connected")
        assertThat(result.connected).isTrue()
        assertThat(result.configured).isTrue()
        assertThat(result.source).isEqualTo("admin")
        assertThat(result.login).isEqualTo("octocat")
        assertThat(result.maskedToken).isEqualTo("ghp_…6789")
        assertThat(result.checkedAt).isNotNull()
        assertThat(userCalls).containsExactly("Bearer $token")
        assertThat(props.effectiveToken).isEqualTo(token)
        assertThat(props.tokenSource).isEqualTo("admin")
        assertThat(storedValue()).isEqualTo(token)
    }

    @Test
    fun `the raw token appears in no dto and no toString`() {
        val props = newProperties()
        val result = newService(props).save(token, "alice")

        assertThat(result.toString()).doesNotContain(token)
        assertThat(props.toString()).doesNotContain(token).contains("<redacted>")
        assertThat(newService(props).status().toString()).doesNotContain(token)
    }

    @Test
    fun `with an encryption key the token is sealed at rest and survives a restart`() {
        val key = "a-strong-key-for-tests"
        val salt = "0123456789abcdef"
        newService(newProperties(), key, salt).save(token, "alice")

        assertThat(storedValue()).startsWith("enc:v1:").doesNotContain(token)

        val restartedProps = newProperties()
        newService(restartedProps, key, salt).loadPersistedToken()
        assertThat(restartedProps.effectiveToken).isEqualTo(token)

        // Another key cannot open it: the token is ignored, not an error.
        val wrongKeyProps = newProperties()
        newService(wrongKeyProps, "another-key", salt).loadPersistedToken()
        assertThat(wrongKeyProps.adminToken).isNull()
        assertThat(wrongKeyProps.effectiveToken).isEmpty()
    }

    @Test
    fun `without a key the token is stored in plain text and survives a restart`() {
        newService().save(token, "alice")

        val restartedProps = newProperties()
        newService(restartedProps).loadPersistedToken()

        assertThat(storedValue()).isEqualTo(token)
        assertThat(restartedProps.effectiveToken).isEqualTo(token)
    }

    @Test
    fun `a token GitHub rejects is not saved and the previous token stays active`() {
        val props = newProperties()
        val subject = newService(props)
        subject.save(token, "alice")
        routes["/user"] = { reply(it, 401, """{"message":"Bad credentials"}""") }

        val result = subject.save("ghp_revokedrevokedrevokedrevokedrevoked1", "bob")

        assertThat(result.status).isEqualTo("invalid")
        assertThat(result.connected).isFalse()
        assertThat(result.message).isEqualTo("GitHub rejected this token (HTTP 401).")
        assertThat(props.effectiveToken).isEqualTo(token)
        assertThat(storedValue()).isEqualTo(token)
    }

    @Test
    fun `a token that cannot be checked because GitHub is unreachable is still saved`() {
        routes["/user"] = { reply(it, 503, "{}") }
        val props = newProperties()

        val result = newService(props).save(token, "alice")

        assertThat(result.status).isEqualTo("unreachable")
        assertThat(result.connected).isFalse()
        assertThat(result.configured).isTrue()
        assertThat(result.message).startsWith("Could not reach GitHub")
        assertThat(props.effectiveToken).isEqualTo(token)
        assertThat(storedValue()).isEqualTo(token)
    }

    @Test
    fun `a blank, spaced or oversized token is a 400 INVALID_VALUE and changes nothing`() {
        val props = newProperties()
        val subject = newService(props)

        listOf(null, "", "   ", "ghp_with space", "ghp_with\nnewline", "x".repeat(513)).forEach { invalid ->
            val error = runCatching { subject.save(invalid, "alice") }.exceptionOrNull()
            assertThat(error).describedAs("value $invalid").isInstanceOf(InvalidGitHubTokenException::class.java)
            error as InvalidGitHubTokenException
            assertThat(error.statusCode).isEqualTo(400)
            assertThat(error.errorCode).isEqualTo("INVALID_VALUE")
        }

        assertThat(userCalls).isEmpty()
        assertThat(props.adminToken).isNull()
        assertThat(storedValue()).isNull()
    }

    @Test
    fun `clearing the admin token falls back to the environment token`() {
        val props = newProperties(environmentToken = "env-token")
        val subject = newService(props)
        subject.save(token, "alice")
        assertThat(props.tokenSource).isEqualTo("admin")

        val result = subject.clear("alice")

        assertThat(props.adminToken).isNull()
        assertThat(props.effectiveToken).isEqualTo("env-token")
        assertThat(result.source).isEqualTo("environment")
        assertThat(result.status).isEqualTo("connected")
        assertThat(storedValue()).isNull()
        assertThat(userCalls.last()).isEqualTo("Bearer env-token")

        val restartedProps = newProperties(environmentToken = "env-token")
        newService(restartedProps).loadPersistedToken()
        assertThat(restartedProps.tokenSource).isEqualTo("environment")
    }

    @Test
    fun `no token anywhere is unconfigured and GitHub is not called`() {
        val result = newService().status()

        assertThat(result.configured).isFalse()
        assertThat(result.connected).isFalse()
        assertThat(result.status).isEqualTo("unconfigured")
        assertThat(result.source).isEqualTo("none")
        assertThat(result.maskedToken).isNull()
        assertThat(userCalls).isEmpty()
    }

    @Test
    fun `a configured token that GitHub rejects is reported invalid with its source`() {
        routes["/user"] = { reply(it, 401, "{}") }

        val result = newService(newProperties(environmentToken = "env-token-stale")).status()

        assertThat(result.status).isEqualTo("invalid")
        assertThat(result.source).isEqualTo("environment")
        assertThat(result.configured).isTrue()
    }

    @Test
    fun `the verification is cached for a short time unless forced, and never across tokens`() {
        var now = 1_000_000L
        val props = newProperties(environmentToken = "env-token")
        val subject = newService(props).also { it.clock = { now } }

        subject.status()
        subject.status()
        assertThat(userCalls).hasSize(1)

        subject.status(forceVerify = true)
        assertThat(userCalls).hasSize(2)

        now += GitHubTokenSettingsService.CACHE_TTL_MILLIS + 1
        subject.status()
        assertThat(userCalls).hasSize(3)

        props.adminToken = token
        subject.status()
        assertThat(userCalls).hasSize(4)
        assertThat(userCalls.last()).isEqualTo("Bearer $token")
    }

    @Test
    fun `a database error while loading leaves the other token sources in charge`() {
        val props = newProperties(environmentToken = "env-token")
        val failing = mockk<Neo4jClient>()
        every { failing.query(any<String>()) } throws IllegalStateException("db down")
        val broken = GitHubTokenSettingsService(failing, props, GitHubChecksClient(props), SecretCipher("NONE", "NONE"))

        broken.loadPersistedToken()

        assertThat(props.adminToken).isNull()
        assertThat(props.effectiveToken).isEqualTo("env-token")
    }
}
