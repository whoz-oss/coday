package io.whozoss.agentos.plugins.mcp

import com.fasterxml.jackson.databind.JsonNode
import com.fasterxml.jackson.module.kotlin.jacksonObjectMapper
import io.whozoss.agentos.sdk.tool.StandardTool
import io.whozoss.agentos.sdk.tool.ToolContext
import io.whozoss.agentos.sdk.tool.ToolPlugin
import mu.KLogging
import org.pf4j.Extension

/**
 * Tool provider for the MCP_HTTP integration type.
 *
 * Each [provideTools] call creates a fresh [HttpMcpConnection], connects to the
 * remote MCP server, discovers tools, and returns [McpTool] wrappers. The connection
 * is NOT pooled — it lives for the duration of the agent run.
 *
 * Authentication is resolved via [ToolContext.credentialProvider]:
 * - If a credential is available, its `accessToken`, `token`, or `key` is used as Bearer token.
 * - If [McpServerConfig.authToken] is set in the config, it is used as a static fallback.
 * - If neither is available, the connection is attempted without authentication.
 */
@Extension
class McpHttpToolProvider : ToolPlugin {
    override val integrationType: String = "MCP_HTTP"

    override val configSchema: JsonNode = CONFIG_SCHEMA

    override fun provideTools(
        config: JsonNode?,
        configName: String?,
        context: ToolContext?,
    ): List<StandardTool<*>> {
        if (config == null || config.isNull) {
            logger.warn { "MCP_HTTP integration '$configName': no config provided, skipping" }
            return emptyList()
        }

        val serverConfig = try {
            McpConfigParser.parse(config)
        } catch (e: IllegalArgumentException) {
            logger.error { "MCP_HTTP integration '$configName': invalid config — ${e.message}" }
            return emptyList()
        }

        if (serverConfig.transport != McpTransport.HTTP) {
            logger.error { "MCP_HTTP integration '$configName': config has transport ${serverConfig.transport}, expected HTTP" }
            return emptyList()
        }

        val bearerToken = resolveBearerToken(context, serverConfig)
        logger.info { "MCP_HTTP integration '$configName': url=${serverConfig.url}, bearerToken=${if (bearerToken != null) "present (${bearerToken.length} chars)" else "absent"}" }

        val connection = HttpMcpConnection(serverConfig)
        return try {
            connection.connect(bearerToken)
            if (connection.tools.isEmpty()) {
                logger.warn { "MCP_HTTP integration '$configName': server advertises no tools" }
                connection.close()
                return emptyList()
            }
            logger.info { "MCP_HTTP integration '$configName': providing ${connection.tools.size} tool(s)" }
            // The connection stays open for the duration of the agent run.
            // McpTool holds a McpConnectionPort reference; callers are responsible for
            // invoking McpConnectionPort.close() when the agent run completes.
            connection.tools.map { McpTool(it, connection, configName) }
        } catch (e: Exception) {
            logger.error(e) { "MCP_HTTP integration '$configName': could not connect — ${e.message}" }
            connection.close()
            emptyList()
        }
    }

    /**
     * Resolves the Bearer token for the HTTP connection.
     *
     * Priority:
     * 1. [ToolContext.credentialProvider] — dynamic, per-user credential (OAuth tokens, API keys)
     * 2. [McpServerConfig.authToken] — static token from IntegrationConfig parameters
     * 3. null — no authentication
     */
    internal fun resolveBearerToken(context: ToolContext?, serverConfig: McpServerConfig): String? {
        val credential = context?.credentialProvider?.invoke()
        if (credential != null) {
            // Try common token key names in priority order
            val token = credential.data["accessToken"]
                ?: credential.data["token"]
                ?: credential.data["key"]
                ?: credential.data["apiKey"]
            if (token != null) {
                logger.debug { "[MCP-HTTP] Using credential from CredentialProvider" }
                return token
            }
        }

        if (serverConfig.authToken != null) {
            logger.debug { "[MCP-HTTP] Using static authToken from config" }
            return serverConfig.authToken
        }

        logger.debug { "[MCP-HTTP] No authentication configured" }
        return null
    }

    companion object : KLogging() {
        private val CONFIG_SCHEMA: JsonNode = jacksonObjectMapper().readTree(
            """
            {
                "type": "object",
                "title": "MCP HTTP Server Configuration",
                "description": "Connects to a remote MCP server over Streamable HTTP transport.",
                "properties": {
                    "url": {
                        "type": "string",
                        "title": "Server URL",
                        "description": "Base URL of the remote MCP server (e.g. https://mcp.example.com). The SDK appends /mcp as the default endpoint."
                    },
                    "authToken": {
                        "type": "string",
                        "title": "Auth Token",
                        "description": "Optional static Bearer token. Overridden by credentials from AuthSetting if configured."
                    },
                    "timeoutSeconds": {
                        "type": "integer",
                        "title": "Connection Timeout (seconds)",
                        "description": "Timeout for the initial MCP handshake.",
                        "default": 30,
                        "minimum": 1
                    },
                    "toolCallTimeoutSeconds": {
                        "type": "integer",
                        "title": "Tool Call Timeout (seconds)",
                        "description": "Timeout for individual tool invocations.",
                        "default": 60,
                        "minimum": 1
                    }
                },
                "required": ["url"],
                "additionalProperties": false
            }
            """.trimIndent()
        )
    }
}
