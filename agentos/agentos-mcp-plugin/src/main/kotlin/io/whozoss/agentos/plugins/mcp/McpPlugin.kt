package io.whozoss.agentos.plugins.mcp

import com.fasterxml.jackson.databind.JsonNode
import com.fasterxml.jackson.module.kotlin.jacksonObjectMapper
import io.whozoss.agentos.sdk.tool.StandardTool
import io.whozoss.agentos.sdk.tool.ToolPlugin
import mu.KLogging
import org.pf4j.Extension
import org.pf4j.Plugin

/**
 * PF4J plugin lifecycle for the MCP plugin.
 *
 * [start] initialises the shared [McpConnectionPool] singleton.
 * [stop] shuts it down, closing all live child processes.
 */
class McpPlugin : Plugin() {
    override fun start() {
        logger.info { "MCP Plugin started" }
        McpConnectionPoolHolder.start()
    }

    override fun stop() {
        logger.info { "MCP Plugin stopping" }
        McpConnectionPoolHolder.shutdown()
    }

    companion object : KLogging()
}

/**
 * Singleton holder for the [McpConnectionPool].
 *
 * Lives in the plugin classloader. Created once when the plugin starts,
 * destroyed when the plugin stops. All [McpToolProvider] instances in the
 * same plugin JAR share the same pool.
 */
object McpConnectionPoolHolder {
    val pool = McpConnectionPool()

    fun start() = pool.start()

    fun shutdown() = pool.shutdown()
}

/**
 * Tool provider for the MCP_STDIO integration type.
 *
 * [provideTools] is called just before each agent run. It:
 * 1. Parses and validates the [IntegrationConfig] parameters.
 * 2. Acquires a (possibly cached) [StdioMcpConnection] from the pool.
 * 3. Returns one [McpTool] per tool advertised by the MCP server.
 *
 * The pool ensures the underlying child process is started at most once per
 * unique server configuration, regardless of how many agent runs are active.
 */
@Extension
class McpToolProvider : ToolPlugin {
    override val integrationType: String = "MCP_STDIO"

    override val configSchema: JsonNode = CONFIG_SCHEMA

    override fun provideTools(
        config: JsonNode?,
        configName: String?,
    ): List<StandardTool<*>> {
        if (config == null || config.isNull) {
            logger.warn { "MCP_STDIO integration '$configName': no config provided, skipping" }
            return emptyList()
        }

        val serverConfig =
            try {
                McpConfigParser.parse(config)
            } catch (e: IllegalArgumentException) {
                logger.error { "MCP_STDIO integration '$configName': invalid config — ${e.message}" }
                return emptyList()
            }

        val connection =
            try {
                McpConnectionPoolHolder.pool.acquire(serverConfig)
            } catch (e: Exception) {
                logger.error { "MCP_STDIO integration '$configName': could not connect — ${e.message}" }
                return emptyList()
            }

        if (connection.tools.isEmpty()) {
            logger.warn { "MCP_STDIO integration '$configName': server advertises no tools" }
            return emptyList()
        }

        logger.info { "MCP_STDIO integration '$configName': providing ${connection.tools.size} tool(s)" }
        return connection.tools.map { McpTool(it, connection, configName) }
    }

    companion object : KLogging() {
        private val CONFIG_SCHEMA: JsonNode =
            jacksonObjectMapper().readTree(
                """
                {
                    "type": "object",
                    "title": "MCP Stdio Server Configuration",
                    "description": "Connects to a local MCP server launched as a child process (stdio transport).",
                    "properties": {
                        "command": {
                            "type": "string",
                            "title": "Command",
                            "description": "Executable to launch (e.g. docker, npx, uvx)."
                        },
                        "args": {
                            "type": "array",
                            "title": "Arguments",
                            "description": "Arguments passed to the command.",
                            "items": { "type": "string" },
                            "default": []
                        },
                        "env": {
                            "type": "object",
                            "title": "Environment Variables",
                            "description": "Environment variables injected into the child process.",
                            "additionalProperties": { "type": "string" },
                            "default": {}
                        },
                        "cwd": {
                            "type": "string",
                            "title": "Working Directory",
                            "description": "Optional working directory for the child process."
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
                        },
                        "idleTimeoutMinutes": {
                            "type": "integer",
                            "title": "Idle Timeout (minutes)",
                            "description": "How long an unused connection stays alive in the pool before eviction.",
                            "default": 10,
                            "minimum": 1
                        }
                    },
                    "required": ["command"],
                    "additionalProperties": false
                }
                """.trimIndent(),
            )
    }
}
