package io.whozoss.agentos.plugins.mcp

/**
 * Thrown by [StdioMcpConnection.callTool] when the MCP server returns a result with
 * [io.modelcontextprotocol.spec.McpSchema.CallToolResult.isError] == true.
 *
 * This is a tool-level application error (the server ran the tool but the tool
 * itself failed), as opposed to a transport or protocol failure ([McpConnectionException]).
 * [content] carries the formatted error text the MCP server returned, suitable for
 * surfacing directly to the agent.
 */
class McpToolErrorException(
    val toolName: String,
    val content: String,
) : RuntimeException("MCP tool '$toolName' returned an error: $content")
