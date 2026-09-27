package io.whozoss.factory.forge

import com.sun.net.httpserver.HttpServer
import io.whozoss.factory.DomainIntegrationTest
import io.whozoss.factory.forge.domain.JiraComment
import io.whozoss.factory.forge.domain.JiraDomain
import io.whozoss.factory.forge.infrastructure.HttpJiraClient
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.boot.test.web.client.TestRestTemplate
import org.springframework.http.HttpEntity
import org.springframework.http.HttpHeaders
import org.springframework.http.HttpMethod
import org.springframework.http.HttpStatus
import org.springframework.web.client.RestClient
import java.net.InetSocketAddress

/**
 * Integration tests of the Jira relay: the `501 JIRA_NOT_CONFIGURED` contract
 * for missing credentials and a real ticket fetch against a local Jira stub.
 */
class JiraProxyHttpTest : DomainIntegrationTest() {

    @Autowired
    private lateinit var restTemplate: TestRestTemplate

    @Test
    fun `missing credentials return 501 JIRA_NOT_CONFIGURED with the exact message`() {
        val response = restTemplate.exchange(
            "/api/jira/PROJ-1",
            HttpMethod.GET,
            HttpEntity<Void>(HttpHeaders()),
            Map::class.java,
        )
        assertThat(response.statusCode).isEqualTo(HttpStatus.valueOf(501))
        val error = response.body?.get("error") as? Map<*, *>
        assertThat(error?.get("code")).isEqualTo("JIRA_NOT_CONFIGURED")
        assertThat(error?.get("message") as String)
            .contains("credentials Jira configurés")
            .contains("JIRA_BASE_URL=https://votre-instance.atlassian.net")
    }

    @Test
    fun `the factory alias also returns 501 when unconfigured`() {
        val response = restTemplate.exchange(
            "/api/factory/jira/PROJ-2",
            HttpMethod.GET,
            HttpEntity<Void>(HttpHeaders()),
            Map::class.java,
        )
        assertThat(response.statusCode).isEqualTo(HttpStatus.valueOf(501))
    }

    @Test
    fun `a configured client fetches a ticket and its comments`() {
        val server = HttpServer.create(InetSocketAddress(0), 0)
        server.createContext("/rest/api/3/issue/PROJ-1/comment") { exchange ->
            val body = """
                {"comments":[{"author":{"displayName":"Alice"},"created":"2026-01-02T03:04:05.000Z",
                "body":{"type":"doc","content":[{"type":"paragraph","content":[{"type":"text","text":"Hello"}]}]}}],"total":1}
            """.trimIndent()
            exchange.responseHeaders.add("Content-Type", "application/json")
            exchange.sendResponseHeaders(200, body.toByteArray().size.toLong())
            exchange.responseBody.use { it.write(body.toByteArray()) }
        }
        server.createContext("/rest/api/3/issue/PROJ-1") { exchange ->
            val body = """
                {"fields":{"summary":"My ticket",
                "description":{"type":"doc","content":[{"type":"paragraph","content":[{"type":"text","text":"Desc"}]}]},
                "parent":{"key":"EPIC-9","fields":{"summary":"Epic summary"}}}}
            """.trimIndent()
            exchange.responseHeaders.add("Content-Type", "application/json")
            exchange.sendResponseHeaders(200, body.toByteArray().size.toLong())
            exchange.responseBody.use { it.write(body.toByteArray()) }
        }
        server.start()
        try {
            val client = HttpJiraClient(
                RestClient.builder(),
                "http://localhost:${server.address.port}",
                "user@example.com",
                "token",
            )
            assertThat(client.isConfigured()).isTrue()
            val ticket = client.fetchTicket("PROJ-1")
            assertThat(ticket.summary).isEqualTo("My ticket")
            assertThat(ticket.epicKey).isEqualTo("EPIC-9")
            assertThat(ticket.ticketContent).contains("## Summary\nMy ticket")
            assertThat(ticket.ticketContent).contains("## Comments")
            assertThat(ticket.ticketContent).contains("**Alice**")
            assertThat(ticket.commentCount).isEqualTo(1)
        } finally {
            server.stop(0)
        }
    }

    @Test
    fun `the pure Jira helpers flatten adf and budget comments`() {
        assertThat(JiraDomain.extractTicketId("https://foo.atlassian.net/browse/proj-1234")).isEqualTo("PROJ-1234")
        assertThat(JiraDomain.extractTicketId("proj-1234")).isEqualTo("PROJ-1234")
        assertThat(JiraDomain.extractTicketId("pas-un-ticket")).isNull()

        val adf = mapOf(
            "type" to "doc",
            "content" to listOf(
                mapOf(
                    "type" to "paragraph",
                    "content" to listOf(mapOf("type" to "text", "text" to "Bonjour")),
                ),
            ),
        )
        assertThat(JiraDomain.extractAdfText(adf).trim()).isEqualTo("Bonjour")

        val comments = (1..3).map { JiraComment("author", "2026-01-01", "body-$it") }
        val (included, omitted) = JiraDomain.applyCommentBudget(comments, 72)
        assertThat(included).hasSize(1)
        assertThat(omitted).isEqualTo(2)
    }
}
