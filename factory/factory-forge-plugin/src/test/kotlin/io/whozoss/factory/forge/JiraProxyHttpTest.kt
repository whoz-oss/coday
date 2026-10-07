package io.whozoss.factory.forge

import com.sun.net.httpserver.HttpServer
import io.whozoss.factory.config.FactoryProperties
import io.whozoss.factory.forge.domain.JiraComment
import io.whozoss.factory.forge.domain.JiraDomain
import io.whozoss.factory.forge.infrastructure.HttpJiraClient
import io.whozoss.factory.forge.port.JiraNotConfiguredException
import io.whozoss.factory.forge.web.JiraProxyController
import io.whozoss.factory.persistence.TenantScopeProvider
import io.whozoss.factory.web.TrustContext
import org.assertj.core.api.Assertions.assertThat
import org.assertj.core.api.Assertions.assertThatThrownBy
import org.junit.jupiter.api.Test
import org.springframework.web.client.RestClient
import java.net.InetSocketAddress

/**
 * Integration tests of the Jira relay handler: the `501 JIRA_NOT_CONFIGURED`
 * contract for missing credentials and a real ticket fetch against a local Jira
 * stub. These run without a Spring context (the plugin is a PF4J plugin).
 */
class JiraProxyHttpTest {

    private val tenantScopeProvider = TenantScopeProvider(FactoryProperties())

    private val trustContext = TrustContext(
        principalId = "user-1",
        organizationId = "org-1",
        workstreamId = "ws-1",
        authenticationMethod = TrustContext.AUTH_LOOPBACK_DEV,
    )

    private fun unconfiguredController() = JiraProxyController(
        HttpJiraClient(RestClient.builder(), null, null, null),
        tenantScopeProvider,
    )

    @Test
    fun `missing credentials raise 501 JIRA_NOT_CONFIGURED with the exact message`() {
        assertThatThrownBy { unconfiguredController().ticket("PROJ-1", trustContext) }
            .isInstanceOf(JiraNotConfiguredException::class.java)
            .hasMessageContaining("credentials Jira configurés")
            .hasMessageContaining("JIRA_BASE_URL=https://votre-instance.atlassian.net")
            .extracting { (it as JiraNotConfiguredException).statusCode }
            .isEqualTo(501)
    }

    @Test
    fun `the factory alias reaches the same unconfigured contract`() {
        val error = runCatching { unconfiguredController().ticket("PROJ-2", trustContext) }.exceptionOrNull()
        assertThat(error).isInstanceOf(JiraNotConfiguredException::class.java)
        assertThat((error as JiraNotConfiguredException).errorCode).isEqualTo("JIRA_NOT_CONFIGURED")
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

            val relayed = JiraProxyController(client, tenantScopeProvider).ticket("PROJ-1", trustContext)
            assertThat(relayed["summary"]).isEqualTo("My ticket")
            assertThat(relayed["epicKey"]).isEqualTo("EPIC-9")
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
