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

    /**
     * Releases any resources held by this connection (HTTP client thread pool,
     * file descriptors, child process, …).
     *
     * The default no-op is intentional: pooled connections ([PooledMcpConnection])
     * declare their own [close] and manage lifecycle through the pool.
     * Ephemeral connections (e.g. [HttpMcpConnection]) override this to perform
     * real cleanup.
     *
     * Safe to call multiple times.
     */
    fun close() {}
}
