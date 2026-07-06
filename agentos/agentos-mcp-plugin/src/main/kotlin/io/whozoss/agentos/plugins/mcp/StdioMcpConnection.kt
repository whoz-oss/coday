package io.whozoss.agentos.plugins.mcp

import io.modelcontextprotocol.client.McpClient
import io.modelcontextprotocol.client.McpSyncClient
import io.modelcontextprotocol.client.transport.ServerParameters
import io.modelcontextprotocol.client.transport.StdioClientTransport
import io.modelcontextprotocol.json.McpJsonDefaults
import io.modelcontextprotocol.spec.McpSchema
import io.modelcontextprotocol.spec.McpSchema.CallToolRequest
import io.modelcontextprotocol.spec.McpSchema.TextContent
import io.modelcontextprotocol.spec.McpSchema.Tool
import mu.KLogging
import java.time.Duration
import java.time.Instant

/**
 * A live connection to a local MCP server process.
 *
 * Wraps [McpSyncClient] from the MCP Java SDK 2.0. The underlying child process is
 * started when [connect] is called and remains alive until [close] is called.
 *
 * [lastUsed] is updated on every [callTool] invocation. The [McpConnectionPool]
 * uses it to evict idle connections.
 *
 * Instances are created and owned exclusively by [McpConnectionPool].
 */
class StdioMcpConnection(
    private val config: McpServerConfig,
    val configHash: String,
) : McpConnectionPort {
    private lateinit var client: McpSyncClient
    private lateinit var transport: StdioClientTransport

    /** Tools discovered at connect time. Immutable after [connect]. */
    override var tools: List<Tool> = emptyList()
        private set

    var lastUsed: Instant = Instant.now()
        private set

    /**
     * Starts the child process, connects the MCP client, and discovers available tools.
     *
     * @throws McpConnectionException if the process fails to start or the MCP handshake times out.
     */
    fun connect() {
        require(config.transport == McpTransport.STDIO) {
            "McpConnection only supports stdio transport; HTTP transport is not yet implemented"
        }
        val command = config.command!!
        logger.info { "[MCP] Connecting to server: $command ${config.args.joinToString(" ")}" }

        val paramsBuilder =
            ServerParameters
                .builder(command)
                .args(config.args)
        if (config.env.isNotEmpty()) {
            paramsBuilder.env(config.env)
        }
        val params = paramsBuilder.build()

        transport = StdioClientTransport(params, McpJsonDefaults.getMapper())

        // The MCP SDK provides a single requestTimeout for ALL JSON-RPC calls
        // (initialize, listTools, callTool, ping). We use the max of the two configured
        // timeouts so that tool calls aren't cut short by the (typically shorter)
        // connection timeout. The handshake timeout is enforced separately below.
        //
        // If the SDK ever adds per-request timeouts, this should be revisited:
        // use config.timeoutSeconds for initialize/listTools and
        // config.toolCallTimeoutSeconds for callTool.
        val clientTimeout = maxOf(config.timeoutSeconds, config.toolCallTimeoutSeconds)
        client =
            McpClient
                .sync(transport)
                .requestTimeout(Duration.ofSeconds(clientTimeout))
                .build()

        try {
            val initResult = client.initialize()
            logger.info { "[MCP] Connected: ${initResult.serverInfo?.name} ${initResult.serverInfo?.version}" }
        } catch (e: Exception) {
            runCatching { client.closeGracefully() }
            throw McpConnectionException("Failed to initialise MCP session for command '$command': ${e.message}", e)
        }

        tools =
            try {
                client.listTools()?.tools ?: emptyList()
            } catch (e: Exception) {
                logger.warn { "[MCP] Could not list tools for '${config.command}': ${e.message}" }
                emptyList()
            }
        logger.info { "[MCP] Discovered ${tools.size} tool(s): ${tools.map { it.name() }.joinToString()}" }
    }

    /**
     * Invokes a tool on the MCP server.
     *
     * @param toolName The name of the MCP tool to call.
     * @param arguments Parsed arguments map (may be empty for no-arg tools).
     * @return The tool result as a plain string.
     */
    override fun callTool(
        toolName: String,
        arguments: Map<String, Any?>,
    ): String {
        lastUsed = Instant.now()
        val request = CallToolRequest.builder(toolName).arguments(arguments as Map<String, Any>).build()
        val result =
            try {
                client.callTool(request)
            } catch (e: Exception) {
                throw McpConnectionException("Tool call '$toolName' failed: ${e.message}", e)
            }
        return formatResult(result)
    }

    /**
     * Checks whether the underlying process is still alive.
     * Used by the pool before returning an existing connection.
     */
    fun isAlive(): Boolean = runCatching { client.ping() }.isSuccess

    /**
     * Closes the MCP session and terminates the child process.
     *
     * Uses a bounded wait strategy to avoid blocking the eviction thread indefinitely
     * if the child process ignores SIGTERM:
     * 1. [McpSyncClient.closeGracefully] sends SIGTERM and waits up to 10 s (SDK default).
     * 2. [McpSyncClient.close] is called as a hard fallback — it calls [Process.destroy]
     *    and disposes the transport without waiting.
     *
     * The previous implementation called [StdioClientTransport.awaitForExit] which
     * blocks indefinitely on [Process.waitFor] — a zombie process would deadlock
     * the eviction thread forever.
     *
     * Safe to call multiple times.
     */
    fun close() {
        logger.info { "[MCP] Closing connection (hash ${configHash.take(8)})" }
        // Attempt graceful shutdown: sends JSON-RPC close, SIGTERM, waits up to 10s.
        val graceful = runCatching { client.closeGracefully() }.getOrDefault(false)
        if (!graceful) {
            // Graceful close timed out or threw — force-close the transport.
            // McpSyncClient.close() calls transport.close() which is non-blocking.
            logger.warn { "[MCP] Graceful close failed, forcing shutdown (hash ${configHash.take(8)})" }
            runCatching { client.close() }
        }
    }

    private fun formatResult(result: McpSchema.CallToolResult): String {
        val content = result.content() ?: return "(no output)"
        val parts =
            content.mapNotNull { item ->
                when (item) {
                    is TextContent -> item.text()
                    else -> "[${item.type()}: unsupported content type]"
                }
            }
        return when {
            parts.isEmpty() -> "(no output)"
            parts.size == 1 -> parts[0]
            else -> parts.joinToString("\n")
        }
    }

    companion object : KLogging()
}

class McpConnectionException(
    message: String,
    cause: Throwable? = null,
) : RuntimeException(message, cause)
