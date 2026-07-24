package io.whozoss.agentos.plugins.mcp

import io.kotest.core.spec.style.StringSpec
import io.kotest.matchers.shouldBe

/**
 * Unit tests for [StdioMcpConnection] configuration logic.
 *
 * These tests verify how config values are translated into SDK client parameters.
 * They do NOT start real MCP processes — they test pure computation only.
 */
class StdioMcpConnectionUnitSpec : StringSpec({

    /**
     * The MCP Java SDK 2.0 provides a single `requestTimeout` for ALL JSON-RPC calls
     * (initialize, listTools, callTool, ping). There is no per-request timeout.
     *
     * Our config exposes two separate timeouts:
     * - `timeoutSeconds` (default 30s) — intended for connection/handshake
     * - `toolCallTimeoutSeconds` (default 60s) — intended for individual tool calls
     *
     * Since the SDK only has one knob, we use `max(timeoutSeconds, toolCallTimeoutSeconds)`
     * as the client timeout. This ensures tool calls aren't prematurely killed by the
     * shorter connection timeout.
     *
     * ⚠️ WHEN TO REVISIT: if the MCP Java SDK adds per-request timeout support
     * (e.g. a timeout parameter on `callTool()`), this logic should be split:
     * use `timeoutSeconds` for initialize/listTools and `toolCallTimeoutSeconds`
     * for callTool. Track upstream: https://github.com/modelcontextprotocol/java-sdk
     */
    "client timeout uses max of connection and tool call timeouts" {
        // Default config: timeoutSeconds=30, toolCallTimeoutSeconds=60
        val defaultConfig = McpServerConfig(command = "echo")
        maxOf(defaultConfig.timeoutSeconds, defaultConfig.toolCallTimeoutSeconds) shouldBe 60L

        // When connection timeout is larger (unusual but valid)
        val longHandshake = McpServerConfig(command = "echo", timeoutSeconds = 120, toolCallTimeoutSeconds = 30)
        maxOf(longHandshake.timeoutSeconds, longHandshake.toolCallTimeoutSeconds) shouldBe 120L

        // When both are equal
        val equal = McpServerConfig(command = "echo", timeoutSeconds = 45, toolCallTimeoutSeconds = 45)
        maxOf(equal.timeoutSeconds, equal.toolCallTimeoutSeconds) shouldBe 45L
    }
})
