package io.whozoss.agentos.plugins.mcp

/**
 * Thrown by [StdioMcpConnection] when the underlying transport or JSON-RPC call fails.
 *
 * This covers transport-level and protocol-level failures (process startup, handshake,
 * network errors), as opposed to tool-level application errors ([McpToolErrorException]).
 */
class McpConnectionException(
    message: String,
    cause: Throwable? = null,
) : RuntimeException(message, cause)
