package io.whozoss.agentos.plugins.mcp

import com.fasterxml.jackson.databind.JsonNode

/**
 * Parses and validates a [JsonNode] config into a [McpServerConfig].
 *
 * Exactly one of `command` (stdio transport) or `url` (HTTP transport) must be present.
 *
 * **stdio validation rules:**
 * - `command` must be non-blank.
 * - `args` entries must not be blank.
 * - `env` must be an object.
 *
 * **HTTP validation rules:**
 * - `url` must be non-blank.
 * - `authToken`, when present, must be non-blank.
 *
 * **Shared validation rules:**
 * - Timeout values, when provided, must be positive.
 *
 * Throws [IllegalArgumentException] with a descriptive message on any violation.
 */
object McpConfigParser {

    fun parse(config: JsonNode): McpServerConfig {
        val command = config.get("command")?.takeIf { !it.isNull }?.asText()?.trim()?.takeIf { it.isNotBlank() }
        val url = config.get("url")?.takeIf { !it.isNull }?.asText()?.trim()?.takeIf { it.isNotBlank() }

        require((command != null) xor (url != null)) {
            when {
                command == null && url == null ->
                    "MCP integration config: exactly one of 'command' (stdio) or 'url' (HTTP) is required"
                else ->
                    "MCP integration config: 'command' and 'url' are mutually exclusive — set only one"
            }
        }

        return when {
            command != null -> parseStdio(config, command)
            else -> parseHttp(config, url!!)
        }
    }

    // ----- stdio -----

    private fun parseStdio(config: JsonNode, command: String): McpServerConfig {
        val args = parseArgs(config)
        val env = parseEnv(config)
        val cwd = config.get("cwd")?.takeIf { !it.isNull }?.asText()?.trim()?.takeIf { it.isNotBlank() }
        val (timeoutSeconds, toolCallTimeoutSeconds, idleTimeoutMinutes) = parseTimeouts(config)

        return McpServerConfig(
            command = command,
            args = args,
            env = env,
            cwd = cwd,
            timeoutSeconds = timeoutSeconds,
            toolCallTimeoutSeconds = toolCallTimeoutSeconds,
            idleTimeoutMinutes = idleTimeoutMinutes,
        )
    }

    private fun parseArgs(config: JsonNode): List<String> {
        val argsNode = config.get("args") ?: return emptyList()
        require(argsNode.isArray) { "MCP integration config: 'args' must be an array" }
        return argsNode.mapIndexed { i, node ->
            val v = node.asText()?.trim()
            require(!v.isNullOrBlank()) { "MCP integration config: args[$i] must not be blank" }
            v
        }
    }

    private fun parseEnv(config: JsonNode): Map<String, String> {
        val envNode = config.get("env") ?: return emptyMap()
        require(envNode.isObject) { "MCP integration config: 'env' must be an object" }
        return envNode.properties().asSequence().associate { (k, v) -> k to v.asText() }
    }

    // ----- HTTP -----

    private fun parseHttp(config: JsonNode, url: String): McpServerConfig {
        val authToken = config.get("authToken")?.takeIf { !it.isNull }?.asText()?.trim()
            ?.also { require(it.isNotBlank()) { "MCP integration config: 'authToken' must not be blank when present" } }
            ?.takeIf { it.isNotBlank() }
        val (timeoutSeconds, toolCallTimeoutSeconds, idleTimeoutMinutes) = parseTimeouts(config)

        return McpServerConfig(
            url = url,
            authToken = authToken,
            timeoutSeconds = timeoutSeconds,
            toolCallTimeoutSeconds = toolCallTimeoutSeconds,
            idleTimeoutMinutes = idleTimeoutMinutes,
        )
    }

    // ----- shared -----

    private data class Timeouts(
        val timeoutSeconds: Long,
        val toolCallTimeoutSeconds: Long,
        val idleTimeoutMinutes: Long,
    )

    private fun parseTimeouts(config: JsonNode): Timeouts {
        val timeoutSeconds = config.get("timeoutSeconds")?.takeIf { !it.isNull }?.asLong()
            ?.also { require(it > 0) { "MCP integration config: 'timeoutSeconds' must be positive, got $it" } }
            ?: DEFAULT_CONNECT_TIMEOUT_SECONDS

        val toolCallTimeoutSeconds = config.get("toolCallTimeoutSeconds")?.takeIf { !it.isNull }?.asLong()
            ?.also { require(it > 0) { "MCP integration config: 'toolCallTimeoutSeconds' must be positive, got $it" } }
            ?: DEFAULT_TOOL_CALL_TIMEOUT_SECONDS

        val idleTimeoutMinutes = config.get("idleTimeoutMinutes")?.takeIf { !it.isNull }?.asLong()
            ?.also { require(it > 0) { "MCP integration config: 'idleTimeoutMinutes' must be positive, got $it" } }
            ?: DEFAULT_IDLE_TIMEOUT_MINUTES

        return Timeouts(timeoutSeconds, toolCallTimeoutSeconds, idleTimeoutMinutes)
    }
}
