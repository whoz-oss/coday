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
})
