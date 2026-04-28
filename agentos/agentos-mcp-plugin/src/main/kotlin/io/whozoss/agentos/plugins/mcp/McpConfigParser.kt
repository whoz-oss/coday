package io.whozoss.agentos.plugins.mcp

import com.fasterxml.jackson.databind.JsonNode

/**
 * Parses and validates a [JsonNode] config into a [McpServerConfig].
 *
 * Validation rules:
 * - [McpServerConfig.command] must be present and non-blank.
 * - [McpServerConfig.args] must not contain blank entries.
 * - Timeout values, when provided, must be positive.
 *
 * Throws [IllegalArgumentException] with a descriptive message on any violation.
 */
object McpConfigParser {

    fun parse(config: JsonNode): McpServerConfig {
        val command = config.get("command")?.asText()?.trim()
        require(!command.isNullOrBlank()) {
            "MCP integration config: 'command' is required and must not be blank"
        }

        val args = when (val argsNode = config.get("args")) {
            null -> emptyList()
            else -> {
                require(argsNode.isArray) { "MCP integration config: 'args' must be an array" }
                argsNode.mapIndexed { i, node ->
                    val v = node.asText()?.trim()
                    require(!v.isNullOrBlank()) {
                        "MCP integration config: args[$i] must not be blank"
                    }
                    v
                }
            }
        }

        val env = when (val envNode = config.get("env")) {
            null -> emptyMap()
            else -> {
                require(envNode.isObject) { "MCP integration config: 'env' must be an object" }
                envNode.fields().asSequence().associate { (k, v) -> k to v.asText() }
            }
        }

        val cwd = config.get("cwd")?.takeIf { !it.isNull }?.asText()?.trim()?.takeIf { it.isNotBlank() }

        val timeoutSeconds = config.get("timeoutSeconds")?.takeIf { !it.isNull }?.asLong()
            ?.also { require(it > 0) { "MCP integration config: 'timeoutSeconds' must be positive, got $it" } }
            ?: DEFAULT_CONNECT_TIMEOUT_SECONDS

        val toolCallTimeoutSeconds = config.get("toolCallTimeoutSeconds")?.takeIf { !it.isNull }?.asLong()
            ?.also { require(it > 0) { "MCP integration config: 'toolCallTimeoutSeconds' must be positive, got $it" } }
            ?: DEFAULT_TOOL_CALL_TIMEOUT_SECONDS

        val idleTimeoutMinutes = config.get("idleTimeoutMinutes")?.takeIf { !it.isNull }?.asLong()
            ?.also { require(it > 0) { "MCP integration config: 'idleTimeoutMinutes' must be positive, got $it" } }
            ?: DEFAULT_IDLE_TIMEOUT_MINUTES

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
}
