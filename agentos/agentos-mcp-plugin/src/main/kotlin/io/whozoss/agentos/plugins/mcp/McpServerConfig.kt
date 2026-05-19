package io.whozoss.agentos.plugins.mcp

/**
 * Configuration for a single MCP server, supporting two mutually exclusive transports:
 *
 * **stdio (local)** — launched as a child process:
 * - [command] must be set; [url] must be absent.
 * - [args], [env], [cwd] are stdio-only fields.
 *
 * **HTTP (remote)** — reached over the network:
 * - [url] must be set; [command] must be absent.
 * - [authToken] is the optional Bearer token sent in the `Authorization` header.
 *
 * The transport in use is determined by [McpServerConfig.transport]:
 * - [McpTransport.STDIO] when [command] is set.
 * - [McpTransport.HTTP] when [url] is set.
 *
 * @property command The executable to launch (stdio transport only).
 * @property args Arguments passed to the command (stdio transport only).
 * @property env Environment variables injected into the child process (stdio transport only).
 * @property cwd Optional working directory for the child process (stdio transport only).
 *   Defaults to the JVM working directory when null.
 * @property url HTTP endpoint of the remote MCP server (HTTP transport only).
 * @property authToken Optional Bearer token sent in the `Authorization` header (HTTP transport only).
 * @property timeoutSeconds Connection/initialisation timeout in seconds.
 *   Defaults to [DEFAULT_CONNECT_TIMEOUT_SECONDS].
 * @property toolCallTimeoutSeconds Per-tool-call timeout in seconds.
 *   Defaults to [DEFAULT_TOOL_CALL_TIMEOUT_SECONDS].
 * @property idleTimeoutMinutes How long a pooled connection may stay idle (no tool calls)
 *   before the pool evicts and closes it.
 *   Defaults to [DEFAULT_IDLE_TIMEOUT_MINUTES].
 */
data class McpServerConfig(
    // stdio transport
    val command: String? = null,
    val args: List<String> = emptyList(),
    val env: Map<String, String> = emptyMap(),
    val cwd: String? = null,
    // HTTP transport
    val url: String? = null,
    val authToken: String? = null,
    // shared
    val timeoutSeconds: Long = DEFAULT_CONNECT_TIMEOUT_SECONDS,
    val toolCallTimeoutSeconds: Long = DEFAULT_TOOL_CALL_TIMEOUT_SECONDS,
    val idleTimeoutMinutes: Long = DEFAULT_IDLE_TIMEOUT_MINUTES,
) {
    val transport: McpTransport
        get() = when {
            command != null -> McpTransport.STDIO
            url != null -> McpTransport.HTTP
            else -> throw IllegalStateException("McpServerConfig has neither 'command' nor 'url' set")
        }

    /** Human-readable identifier for logging — command for stdio, url for HTTP. */
    val label: String
        get() = command ?: url ?: "(unconfigured)"
}

enum class McpTransport { STDIO, HTTP }

const val DEFAULT_CONNECT_TIMEOUT_SECONDS = 30L
const val DEFAULT_TOOL_CALL_TIMEOUT_SECONDS = 60L
const val DEFAULT_IDLE_TIMEOUT_MINUTES = 10L
