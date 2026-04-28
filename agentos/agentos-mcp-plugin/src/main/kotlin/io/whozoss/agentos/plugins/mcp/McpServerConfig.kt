package io.whozoss.agentos.plugins.mcp

/**
 * Configuration for a single local MCP server (stdio transport).
 *
 * @property command The executable to launch (e.g. `docker`, `npx`, `uvx`).
 * @property args Arguments passed to the command.
 * @property env Environment variables injected into the child process.
 * @property cwd Optional working directory for the child process.
 *   Defaults to the JVM working directory when null.
 * @property timeoutSeconds Connection timeout when initialising the MCP session.
 *   Defaults to [DEFAULT_CONNECT_TIMEOUT_SECONDS].
 * @property toolCallTimeoutSeconds Per-tool-call timeout in seconds.
 *   Defaults to [DEFAULT_TOOL_CALL_TIMEOUT_SECONDS].
 * @property idleTimeoutMinutes How long a pooled connection may stay idle (no tool calls)
 *   before the pool evicts and closes it.
 *   Defaults to [DEFAULT_IDLE_TIMEOUT_MINUTES].
 */
data class McpServerConfig(
    val command: String,
    val args: List<String> = emptyList(),
    val env: Map<String, String> = emptyMap(),
    val cwd: String? = null,
    val timeoutSeconds: Long = DEFAULT_CONNECT_TIMEOUT_SECONDS,
    val toolCallTimeoutSeconds: Long = DEFAULT_TOOL_CALL_TIMEOUT_SECONDS,
    val idleTimeoutMinutes: Long = DEFAULT_IDLE_TIMEOUT_MINUTES,
)

const val DEFAULT_CONNECT_TIMEOUT_SECONDS = 30L
const val DEFAULT_TOOL_CALL_TIMEOUT_SECONDS = 60L
const val DEFAULT_IDLE_TIMEOUT_MINUTES = 10L
