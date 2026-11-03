package io.whozoss.agentos.plugins.mcp

import ch.qos.logback.classic.Level
import com.fasterxml.jackson.databind.JsonNode
import com.fasterxml.jackson.module.kotlin.jacksonObjectMapper
import com.sun.net.httpserver.HttpServer
import io.kotest.assertions.throwables.shouldThrow
import io.kotest.core.spec.style.StringSpec
import io.kotest.matchers.collections.shouldBeEmpty
import io.kotest.matchers.collections.shouldHaveSize
import io.kotest.matchers.shouldBe
import io.kotest.matchers.string.shouldContain
import io.kotest.matchers.string.shouldNotContain
import java.net.InetSocketAddress

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

    "connect fails when the server completes the handshake but refuses to list its tools" {
        // Like an OAuth-protected server reached without a token: initialize passes, tools/list answers 401.
        // An empty tool list would hide the failure, as if the server advertised no tools.
        val server = handshakeOnlyServer()
        try {
            val config = McpServerConfig(
                url = "http://127.0.0.1:${server.address.port}/mcp",
                timeoutSeconds = 2,
                toolCallTimeoutSeconds = 2,
            )
            val ex = shouldThrow<McpConnectionException> { HttpMcpConnection(config).connect(authorization = null) }
            ex.message shouldContain "list the tools"
        } finally {
            server.stop(0)
        }
    }

    "connect reports no tools when the server does not advertise the tools capability" {
        // A server that offers only resources or prompts: the SDK refuses tools/list on its own, and we never
        // ask. Reporting the integration as failed would warn the user on every message for a working server.
        val server = handshakeOnlyServer(advertisesTools = false)
        try {
            val config = McpServerConfig(
                url = "http://127.0.0.1:${server.address.port}/mcp",
                timeoutSeconds = 2,
                toolCallTimeoutSeconds = 2,
            )
            val connection = HttpMcpConnection(config)
            val logs = LogCapture.capturing { connection.connect(authorization = null) }

            connection.tools.shouldBeEmpty()
            logs.messagesAt(Level.WARN).single() shouldContain "does not advertise the tools capability"
        } finally {
            server.stop(0)
        }
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

        private val mapper = jacksonObjectMapper()

        /**
         * A Streamable HTTP MCP server on an ephemeral loopback port that completes the handshake, then answers 401
         * to every request (tools/list included). GET, the optional server-sent event stream, is not supported.
         *
         * @param advertisesTools whether the handshake declares the `tools` capability. Without it the SDK refuses
         *   to list tools client-side, which is a server without tools rather than a failed connection.
         */
        private fun handshakeOnlyServer(advertisesTools: Boolean = true): HttpServer =
            HttpServer.create(InetSocketAddress("127.0.0.1", 0), 0).apply {
                createContext("/") { exchange ->
                    exchange.use {
                        val bytes = it.requestBody.readBytes()
                        val request = if (bytes.isEmpty()) null else mapper.readTree(bytes)
                        val (status, body) = when {
                            it.requestMethod != "POST" -> 405 to ""
                            request?.get("method")?.asText() == "initialize" ->
                                200 to initializeResult(request, advertisesTools)
                            request?.get("id") == null -> 202 to ""
                            else -> 401 to ""
                        }
                        if (body.isEmpty()) {
                            it.sendResponseHeaders(status, -1)
                        } else {
                            val payload = body.toByteArray()
                            it.responseHeaders.add("Content-Type", "application/json")
                            it.sendResponseHeaders(status, payload.size.toLong())
                            it.responseBody.write(payload)
                        }
                    }
                }
                start()
            }

        private fun initializeResult(request: JsonNode, advertisesTools: Boolean): String {
            val capabilities = if (advertisesTools) mapOf("tools" to emptyMap<String, Any>()) else emptyMap()
            return mapper.writeValueAsString(
                mapOf(
                    "jsonrpc" to "2.0",
                    "id" to request["id"],
                    "result" to mapOf<String, Any>(
                        "protocolVersion" to request["params"]["protocolVersion"],
                        "capabilities" to capabilities,
                        "serverInfo" to mapOf("name" to "test", "version" to "1"),
                    ),
                ),
            )
        }
    }
}
