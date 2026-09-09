package io.whozoss.agentos.plugins.mcp

import com.fasterxml.jackson.databind.JsonNode
import com.fasterxml.jackson.module.kotlin.jacksonObjectMapper
import io.whozoss.agentos.sdk.credential.Credential
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
 * Authentication is resolved by [resolveAuthorization]:
 * - If [ToolContext.credentialProvider] yields a credential, its [Credential.credentialType] selects
 *   the header scheme via [AuthorizationHeader.from] (Bearer for OAuth/API key/Bearer token,
 *   Basic for BASIC_AUTH).
 * - Otherwise, if the deprecated [McpServerConfig.authToken] is set, it is sent as a Bearer token.
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

        val authorization = resolveAuthorization(context, serverConfig)
        logger.info {
            "MCP_HTTP integration '$configName': url=${serverConfig.url}, authorization=${authorization ?: "absent"}"
        }

        val connection = HttpMcpConnection(serverConfig)
        return try {
            connection.connect(authorization)
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
     * Resolves the `Authorization` header for the HTTP connection.
     *
     * Priority:
     * 1. [ToolContext.credentialProvider] — per-user credential mapped by [AuthorizationHeader.from].
     *    A credential whose material is missing or blank is reported and skipped.
     * 2. [McpServerConfig.authToken] — deprecated static Bearer token; an Auth Setting should be bound instead.
     * 3. `null` — no authentication.
     */
    internal fun resolveAuthorization(context: ToolContext?, serverConfig: McpServerConfig): AuthorizationHeader? =
        context?.credentialProvider?.invoke()?.let { fromCredential(it, serverConfig) }
            ?: fromStaticToken(serverConfig)

    private fun fromCredential(credential: Credential, serverConfig: McpServerConfig): AuthorizationHeader? {
        val authorization = AuthorizationHeader.from(credential)
        if (authorization == null) {
            logger.warn {
                "MCP_HTTP integration '${serverConfig.label}': credential of type ${credential.credentialType} " +
                    "carries no usable material; ignoring it"
            }
        } else {
            logger.debug { "[MCP-HTTP] Using ${credential.credentialType} credential from CredentialProvider" }
        }
        return authorization
    }

    private fun fromStaticToken(serverConfig: McpServerConfig): AuthorizationHeader? {
        val authToken = serverConfig.authToken
        if (authToken == null) {
            logger.debug { "[MCP-HTTP] No authentication configured" }
            return null
        }
        logger.warn {
            "MCP_HTTP integration '${serverConfig.label}': static 'authToken' is deprecated, " +
                "bind an Auth Setting to the integration instead"
        }
        return AuthorizationHeader.Bearer(authToken)
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
                        "format": "uri",
                        "title": "Server URL",
                        "description": "Full URL of the MCP endpoint, path included, exactly as documented by the server provider; no path is appended automatically (examples: 'https://mcp.hubspot.com/' root endpoint, 'https://mcp.atlassian.net/v1/mcp' path endpoint). Must be an absolute http(s) URL with a public host: localhost, loopback, link-local, private, shared-address-space (CGNAT) and wildcard IPs are rejected, and embedded user:password is not allowed. Prefer https: credentials sent over plain http travel in cleartext."
                    },
                    "authToken": {
                        "type": "string",
                        "title": "Auth Token",
                        "description": "Deprecated: bind an Auth Setting instead; kept as a fallback. Static Bearer token used only when the bound Auth Setting yields no usable credential."
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
