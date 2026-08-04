package io.whozoss.agentos.plugins.mcp

import io.modelcontextprotocol.client.McpClient
import io.modelcontextprotocol.client.McpSyncClient
import io.modelcontextprotocol.client.transport.HttpClientStreamableHttpTransport
import io.modelcontextprotocol.spec.McpSchema
import io.modelcontextprotocol.spec.McpSchema.CallToolRequest
import io.modelcontextprotocol.spec.McpSchema.TextContent
import io.modelcontextprotocol.spec.McpSchema.Tool
import mu.KLogging
import java.net.http.HttpRequest
import java.time.Duration

/**
 * A live connection to a remote MCP server over Streamable HTTP.
 *
 * Unlike [StdioMcpConnection], HTTP connections are NOT pooled — each instance is
 * created per agent run and closed after tool resolution. The [CredentialProvider]
 * supplies per-user auth tokens, making connection sharing across users unsafe.
 *
 * Uses [HttpClientStreamableHttpTransport] from the MCP Java SDK 2.0.
 *
 * The URL from [McpServerConfig.url] is automatically split into base URI and endpoint
 * path to work around the MCP Java SDK's `URI.resolve` behavior. When the URL includes
 * a path (e.g. `https://mcp.example.com/v1/mcp`), the path is extracted as the explicit
 * endpoint. When the URL has no path (e.g. `https://mcp.example.com`), the SDK's default
 * `/mcp` endpoint is used.
 */
class HttpMcpConnection(
    private val config: McpServerConfig,
) : McpConnectionPort {
    private lateinit var client: McpSyncClient

    override var tools: List<Tool> = emptyList()
        private set

    /**
     * Connects to the remote MCP server, performs the MCP handshake, and discovers tools.
     *
     * Bearer token injection uses [HttpClientStreamableHttpTransport.Builder.requestBuilder]:
     * a pre-configured [HttpRequest.Builder] with the `Authorization` header is passed to
     * the transport. The builder is copied internally on every request, so the header
     * is applied consistently across all JSON-RPC calls (initialize, listTools, callTool).
     *
     * @param bearerToken Optional Bearer token for the `Authorization` header.
     *   When non-null, injected via a pre-configured [HttpRequest.Builder].
     * @throws McpConnectionException if the HTTP connection or MCP handshake fails.
     */
    fun connect(bearerToken: String? = null) {
        require(config.transport == McpTransport.HTTP) {
            "HttpMcpConnection requires HTTP transport config"
        }
        val url = config.url!!
        logger.info { "[MCP-HTTP] Connecting to server: $url" }

        val clientTimeout = maxOf(config.timeoutSeconds, config.toolCallTimeoutSeconds)

        // The MCP Java SDK appends a default endpoint path ("/mcp") to the base URI.
        // When the configured URL already includes the full MCP path (e.g. ends with
        // "/mcp" or "/mcp/authv2"), we must split it into base + endpoint to prevent
        // the SDK from producing a wrong URL like "https://host/v1/mcp" → "https://host/mcp".
        // URI.resolve("/mcp") replaces the entire path — it does NOT append.
        val (baseUrl, endpoint) = splitMcpUrl(url)
        logger.debug { "[MCP-HTTP] Resolved baseUrl=$baseUrl, endpoint=$endpoint" }

        val transportBuilder = HttpClientStreamableHttpTransport
            .builder(baseUrl)
            .also { if (endpoint != null) it.endpoint(endpoint) }
            .connectTimeout(Duration.ofSeconds(config.timeoutSeconds))

        // Inject Bearer token via a pre-configured request builder.
        // The transport copies this builder for each request, so the header is applied
        // to every JSON-RPC call without needing a per-request customizer.
        if (bearerToken != null) {
            transportBuilder.requestBuilder(
                HttpRequest.newBuilder().header("Authorization", "Bearer $bearerToken")
            )
        }

        val transport = transportBuilder.build()

        client = McpClient
            .sync(transport)
            .requestTimeout(Duration.ofSeconds(clientTimeout))
            .build()

        try {
            val initResult = client.initialize()
            logger.info { "[MCP-HTTP] Connected: ${initResult.serverInfo?.name} ${initResult.serverInfo?.version}" }
        } catch (e: Exception) {
            runCatching { client.closeGracefully() }
            throw McpConnectionException("Failed to initialise MCP HTTP session for '$url': ${e.message}", e)
        }

        tools = try {
            client.listTools()?.tools ?: emptyList()
        } catch (e: Exception) {
            logger.warn { "[MCP-HTTP] Could not list tools for '$url': ${e.message}" }
            emptyList()
        }
        logger.info { "[MCP-HTTP] Discovered ${tools.size} tool(s)" }
    }

    override fun callTool(toolName: String, arguments: Map<String, Any?>): String {
        try {
            val safeArguments: Map<String, Any> = arguments.filterValues { it != null }.mapValues { it.value!! }
            val request = CallToolRequest.builder(toolName)
                .arguments(safeArguments)
                .build()
            val result = try {
                client.callTool(request)
            } catch (e: Exception) {
                throw McpConnectionException("Tool call '$toolName' failed: ${e.message}", e)
            }
            val formatted = formatResult(result)
            return if (result.isError == true) {
                throw McpToolErrorException(toolName, formatted)
            } else {
                formatted
            }
        } catch (e: McpToolErrorException) {
            throw e
        } catch (e: McpConnectionException) {
            throw e
        } catch (e: Exception) {
            throw McpConnectionException("Unexpected error calling tool '$toolName': ${e.message}", e)
        }
    }

    /**
     * Closes the MCP HTTP session gracefully.
     * Safe to call multiple times — failures on close are swallowed.
     */
    override fun close() {
        logger.debug { "[MCP-HTTP] Closing connection to ${config.url}" }
        runCatching { client.closeGracefully() }
            .onFailure { runCatching { client.close() } }
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

    companion object : KLogging() {
        /**
         * Splits a full MCP URL into (baseUrl, endpoint) for the MCP Java SDK.
         *
         * The SDK's [HttpClientStreamableHttpTransport] resolves the final request URI as
         * `URI.resolve(baseUri, endpoint)`. Because `endpoint` defaults to `"/mcp"` (an
         * absolute path), `URI.resolve` **replaces** the entire base path rather than
         * appending. For example:
         *
         *   baseUri = "https://mcp.atlassian.com/v1/mcp"
         *   endpoint = "/mcp"
         *   resolved = "https://mcp.atlassian.com/mcp"  ← WRONG
         *
         * To work around this, when the configured URL already contains the full path to
         * the MCP endpoint, we split it into a base (scheme + authority) and the path as
         * the explicit endpoint. This way `URI.resolve` produces the correct result.
         *
         * When the URL has no meaningful path (e.g. `https://mcp.example.com`), we return
         * `null` for the endpoint and let the SDK use its default `"/mcp"`.
         */
        internal fun splitMcpUrl(url: String): Pair<String, String?> {
            val uri = java.net.URI.create(url)
            val path = uri.path
            // If the URL has a meaningful path (more than just "/"), extract it as the endpoint
            // and use the origin (scheme + authority) as the base URL.
            return if (!path.isNullOrBlank() && path != "/") {
                val origin = "${uri.scheme}://${uri.authority}"
                Pair(origin, path)
            } else {
                // No path — let the SDK use its default "/mcp" endpoint
                Pair(url, null)
            }
        }
    }
}
