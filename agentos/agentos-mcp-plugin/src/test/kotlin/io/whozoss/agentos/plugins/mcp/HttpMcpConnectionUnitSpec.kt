package io.whozoss.agentos.plugins.mcp

import io.kotest.assertions.throwables.shouldThrow
import io.kotest.core.spec.style.StringSpec
import io.kotest.matchers.shouldBe

/**
 * Unit tests for [HttpMcpConnection] covering the parts that are testable
 * without a real HTTP server (config validation and transport preconditions).
 *
 * The network-dependent path (connect, callTool, close) requires a live MCP server
 * and is therefore covered by integration tests, not here.
 */
class HttpMcpConnectionUnitSpec : StringSpec({

    "connect rejects stdio config" {
        val stdioConfig = McpServerConfig(command = "docker")
        val connection = HttpMcpConnection(stdioConfig)
        val ex = shouldThrow<IllegalArgumentException> {
            connection.connect()
        }
        ex.message shouldBe "HttpMcpConnection requires HTTP transport config"
    }

    "tools list is empty before connect" {
        val config = McpServerConfig(url = "https://mcp.example.com")
        val connection = HttpMcpConnection(config)
        connection.tools shouldBe emptyList()
    }
})
