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
 * supplies per-user credentials, making connection sharing across users unsafe.
 *
 * Uses [HttpClientStreamableHttpTransport] from the MCP Java SDK 2.0.
 *
 * The URL from [McpServerConfig.url] is automatically split into base URI and endpoint
 * path to work around the MCP Java SDK's `URI.resolve` behavior. The endpoint is **always**
 * set explicitly — the SDK's implicit default `/mcp` is never used:
 *
 * - URL with a meaningful path (e.g. `https://mcp.atlassian.com/v1/mcp`): the path is
 *   extracted as the explicit endpoint (`/v1/mcp`), and the origin becomes the base URI.
 * - URL with no path or root path (e.g. `https://mcp.hubspot.com` or
 *   `https://mcp.hubspot.com/`): the endpoint is set to `"/"` so the server root is hit.
 *
 * This matters because the default `/mcp` is not universal: HubSpot exposes the MCP
 * endpoint at the root (`/`), while Atlassian uses `/v1/mcp`.
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
     * Header injection uses [HttpClientStreamableHttpTransport.Builder.requestBuilder]:
     * a pre-configured [HttpRequest.Builder] with the `Authorization` header is passed to
     * the transport. The builder is copied internally on every request, so the header
     * is applied consistently across all JSON-RPC calls (initialize, listTools, callTool).
     *
     * @param authorization Optional `Authorization` header (Bearer or Basic). When non-null,
     *   its [AuthorizationHeader.headerValue] is injected via a pre-configured [HttpRequest.Builder].
     *   Sending it over plain `http` exposes the credential in cleartext and is logged as a warning
     *   (this covers bound credentials; [McpConfigParser] already warns for a static `authToken`).
     * @throws McpConnectionException if the HTTP connection or MCP handshake fails.
     */
    fun connect(authorization: AuthorizationHeader? = null) {
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
        logger.info { "[MCP-HTTP] Resolved baseUrl=$baseUrl, endpoint=$endpoint" }

        val transportBuilder = HttpClientStreamableHttpTransport
            .builder(baseUrl)
            .also { if (endpoint != null) it.endpoint(endpoint) }
            .connectTimeout(Duration.ofSeconds(config.timeoutSeconds))

        // Inject the Authorization header via a pre-configured request builder.
        // The transport copies this builder for each request, so the header is applied
        // to every JSON-RPC call without needing a per-request customizer.
        if (authorization != null) {
            warnIfCleartext(url)
            transportBuilder.requestBuilder(
                HttpRequest.newBuilder().header("Authorization", authorization.headerValue())
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

    private fun warnIfCleartext(url: String) {
        val uri = java.net.URI.create(url)
        if (uri.scheme.equals("http", ignoreCase = true)) {
            logger.warn { "[MCP-HTTP] Sending credentials over cleartext http to '${uri.host}' — use https" }
        }
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
         * To prevent this, we always split the URL into origin (scheme + authority) and
         * an explicit endpoint path, so the SDK's implicit default `"/mcp"` never applies.
         *
         * The SDK's default is not universal: HubSpot exposes MCP at the root (`/`),
         * while Atlassian uses `/v1/mcp`. Letting the SDK silently fall back to `/mcp`
         * would make root-endpoint servers unreachable.
         *
         * @return a pair of (origin, endpoint) where:
         *   - `origin` is always `scheme://authority` (port preserved when explicit)
         *   - `endpoint` is the path from the URL, or `"/"` when the URL has no path
         *     (the second element is typed as [String?] but is never null in practice)
         */
        internal fun splitMcpUrl(url: String): Pair<String, String?> {
            val uri = java.net.URI.create(url)
            val origin = "${uri.scheme}://${uri.authority}"
            val path = uri.path
            // Always set an explicit endpoint so the SDK's implicit "/mcp" default never applies.
            // Root-only URLs (empty path or bare "/") get endpoint="/" to target the server root.
            return if (!path.isNullOrBlank() && path != "/") {
                Pair(origin, path)
            } else {
                Pair(origin, "/")
            }
        }
    }
}
