package io.whozoss.agentos.plugins.mcp

import ch.qos.logback.classic.Level
import io.kotest.assertions.throwables.shouldThrow
import io.kotest.core.spec.style.StringSpec
import io.kotest.matchers.collections.shouldBeEmpty
import io.kotest.matchers.collections.shouldHaveSize
import io.kotest.matchers.shouldBe
import io.kotest.matchers.string.shouldContain
import io.kotest.matchers.string.shouldNotContain

/**
 * Unit tests for [HttpMcpConnection] covering the parts that are testable
 * without a real HTTP server (config validation and transport preconditions).
 *
 * The network-dependent path (connect, callTool, close) requires a live MCP server
 * and is therefore covered by integration tests, not here. The cleartext warning is
 * emitted before any network I/O, so it is asserted against a closed loopback port
 * (connection refused, no live server needed).
 */
class HttpMcpConnectionUnitSpec : StringSpec({

    "connect rejects stdio config" {
        val stdioConfig = McpServerConfig(command = "docker")
        val connection = HttpMcpConnection(stdioConfig)
        val ex = shouldThrow<IllegalArgumentException> {
            connection.connect(authorization = AuthorizationHeader.Bearer("unused"))
        }
        ex.message shouldBe "HttpMcpConnection requires HTTP transport config"
    }

    "tools list is empty before connect" {
        val config = McpServerConfig(url = "https://mcp.example.com")
        val connection = HttpMcpConnection(config)
        connection.tools shouldBe emptyList()
    }

    "connect warns about cleartext http when an Authorization header is sent, without echoing it" {
        // HttpMcpConnection does not validate the host (McpConfigParser does), so a closed
        // loopback port gives a fast, deterministic connection failure after the warning.
        val config = McpServerConfig(url = "http://127.0.0.1:$UNUSED_PORT/mcp", timeoutSeconds = 1)
        val connection = HttpMcpConnection(config)
        val logs = LogCapture.capturing {
            shouldThrow<McpConnectionException> {
                connection.connect(authorization = AuthorizationHeader.Bearer("secret-token"))
            }
        }
        val warnings = logs.messagesAt(Level.WARN)
        warnings shouldHaveSize 1
        warnings.single() shouldContain "cleartext"
        warnings.single() shouldContain "127.0.0.1"
        logs.messages.forEach { it shouldNotContain "secret-token" }
    }

    "connect does not warn about cleartext when no Authorization header is sent" {
        val config = McpServerConfig(url = "http://127.0.0.1:$UNUSED_PORT/mcp", timeoutSeconds = 1)
        val connection = HttpMcpConnection(config)
        val logs = LogCapture.capturing {
            shouldThrow<McpConnectionException> { connection.connect(authorization = null) }
        }
        logs.messagesAt(Level.WARN).shouldBeEmpty()
    }

    // splitMcpUrl — the endpoint is always explicit; the SDK default "/mcp" never applies

    "splitMcpUrl: bare root URL (no path) returns origin and slash endpoint" {
        HttpMcpConnection.splitMcpUrl("https://mcp.hubspot.com") shouldBe
            Pair("https://mcp.hubspot.com", "/")
    }

    "splitMcpUrl: root URL with trailing slash returns origin and slash endpoint" {
        HttpMcpConnection.splitMcpUrl("https://mcp.hubspot.com/") shouldBe
            Pair("https://mcp.hubspot.com", "/")
    }

    "splitMcpUrl: URL with explicit path returns origin and that path as endpoint" {
        HttpMcpConnection.splitMcpUrl("https://mcp.atlassian.com/v1/mcp") shouldBe
            Pair("https://mcp.atlassian.com", "/v1/mcp")
    }

    "splitMcpUrl: URL with single-segment path returns origin and that path as endpoint" {
        HttpMcpConnection.splitMcpUrl("https://mcp.example.com/mcp") shouldBe
            Pair("https://mcp.example.com", "/mcp")
    }

    "splitMcpUrl: URL with port preserves the port in origin and returns slash endpoint" {
        HttpMcpConnection.splitMcpUrl("http://localhost:3000") shouldBe
            Pair("http://localhost:3000", "/")
    }
}) {
    companion object {
        /** Port 1 (tcpmux) is never bound on a developer machine or CI runner: connect is refused at once. */
        private const val UNUSED_PORT = 1
    }
}
