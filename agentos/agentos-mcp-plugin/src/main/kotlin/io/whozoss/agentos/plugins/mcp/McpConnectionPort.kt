package io.whozoss.agentos.plugins.mcp

import io.modelcontextprotocol.spec.McpSchema.Tool

/**
 * Abstraction over a live MCP server connection.
 *
 * Extracted as an interface so that [McpTool] can be unit-tested without
 * spinning up a real child process.
 */
interface McpConnectionPort {
    /** Tools discovered at connect time. */
    val tools: List<Tool>

    /**
     * Invokes a tool on the MCP server and returns the result as a plain string.
     */
    fun callTool(toolName: String, arguments: Map<String, Any?>): String
}
