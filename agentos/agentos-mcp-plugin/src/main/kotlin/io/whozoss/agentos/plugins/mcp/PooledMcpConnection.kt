package io.whozoss.agentos.plugins.mcp

import io.modelcontextprotocol.spec.McpSchema.Tool
import java.time.Instant

/**
 * The subset of [StdioMcpConnection] that [McpConnectionPool] needs to manage
 * a pooled connection: liveness check, idle-time tracking, graceful close,
 * and the tool list used when creating [McpTool] instances.
 *
 * Extracting this interface allows tests to inject mock implementations
 * without having to subclass the final [StdioMcpConnection] class.
 */
interface PooledMcpConnection : McpConnectionPort {
    /** When the connection was last used for a tool call. */
    val lastUsed: Instant

    /** Returns true if the underlying server process is still alive. */
    fun isAlive(): Boolean

    /** Closes the connection and terminates the underlying server process. */
    override fun close()

    /** Tools discovered when the connection was established. */
    override val tools: List<Tool>
}
