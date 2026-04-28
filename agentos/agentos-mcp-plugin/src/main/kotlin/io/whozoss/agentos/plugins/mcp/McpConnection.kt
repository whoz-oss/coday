package io.whozoss.agentos.plugins.mcp

import io.modelcontextprotocol.client.McpClient
import io.modelcontextprotocol.client.McpSyncClient
import io.modelcontextprotocol.client.transport.ServerParameters
import io.modelcontextprotocol.client.transport.StdioClientTransport
import io.modelcontextprotocol.json.McpJsonMapper
import io.modelcontextprotocol.spec.McpSchema
import io.modelcontextprotocol.spec.McpSchema.CallToolRequest
import io.modelcontextprotocol.spec.McpSchema.Content
import io.modelcontextprotocol.spec.McpSchema.TextContent
import io.modelcontextprotocol.spec.McpSchema.Tool
import mu.KLogging
import java.time.Duration
import java.time.Instant

/**
 * A live connection to a local MCP server process.
 *
 * Wraps [McpSyncClient] from the MCP Java SDK 0.17.x. The underlying child process is
 * started when [connect] is called and remains alive until [close] is called.
 *
 * [lastUsed] is updated on every [callTool] invocation. The [McpConnectionPool]
 * uses it to evict idle connections.
 *
 * Instances are created and owned exclusively by [McpConnectionPool].
 */
class McpConnection(
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
        logger.info { "[MCP] Connecting to server: ${config.command} ${config.args.joinToString(" ")}" }

        val paramsBuilder = ServerParameters.builder(config.command)
            .args(config.args)
        if (config.env.isNotEmpty()) {
            paramsBuilder.env(config.env)
        }
        val params = paramsBuilder.build()

        transport = StdioClientTransport(params, McpJsonMapper.getDefault())

        client = McpClient.sync(transport)
            .requestTimeout(Duration.ofSeconds(config.timeoutSeconds.toLong()))
            .build()

        try {
            val initResult = client.initialize()
            logger.info { "[MCP] Connected: ${initResult.serverInfo?.name} ${initResult.serverInfo?.version}" }
        } catch (e: Exception) {
            runCatching { client.closeGracefully() }
            throw McpConnectionException("Failed to initialise MCP session for command '${config.command}': ${e.message}", e)
        }

        tools = try {
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
    override fun callTool(toolName: String, arguments: Map<String, Any?>): String {
        lastUsed = Instant.now()
        val request = CallToolRequest(toolName, arguments as Map<String, Any>)
        val result = try {
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
     * Safe to call multiple times.
     */
    fun close() {
        logger.info { "[MCP] Closing connection (hash ${configHash.take(8)})" }
        runCatching { client.closeGracefully() }
        runCatching { transport.awaitForExit() }
    }

    private fun formatResult(result: McpSchema.CallToolResult): String {
        val content = result.content() ?: return "(no output)"
        val parts = content.mapNotNull { item ->
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

class McpConnectionException(message: String, cause: Throwable? = null) : RuntimeException(message, cause)
