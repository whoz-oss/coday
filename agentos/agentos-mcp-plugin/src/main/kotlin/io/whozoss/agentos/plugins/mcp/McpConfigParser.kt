package io.whozoss.agentos.plugins.mcp

import com.fasterxml.jackson.databind.DeserializationFeature
import com.fasterxml.jackson.databind.JsonNode
import com.fasterxml.jackson.databind.ObjectMapper
import com.fasterxml.jackson.module.kotlin.registerKotlinModule

/**
 * Parses and validates a [JsonNode] config into a [McpServerConfig].
 *
 * Delegates structural mapping to Jackson, then validates business rules:
 *
 * - Exactly one of `command` (stdio transport) or `url` (HTTP transport) must be present.
 * - `command` and `url` must be non-blank.
 * - `args` entries must not be blank.
 * - `authToken`, when present, must be non-blank.
 * - Timeout values, when provided, must be positive.
 *
 * Throws [IllegalArgumentException] with a descriptive message on any violation.
 */
object McpConfigParser {

    private val mapper = ObjectMapper()
        .registerKotlinModule()
        .configure(DeserializationFeature.FAIL_ON_UNKNOWN_PROPERTIES, false)

    fun parse(config: JsonNode): McpServerConfig {
        val raw = mapper.treeToValue(config, McpServerConfig::class.java)
        return validate(raw)
    }

    private fun validate(config: McpServerConfig): McpServerConfig {
        val hasCommand = !config.command.isNullOrBlank()
        val hasUrl = !config.url.isNullOrBlank()

        require(hasCommand xor hasUrl) {
            when {
                !hasCommand && !hasUrl ->
                    "MCP integration config: exactly one of 'command' (stdio) or 'url' (HTTP) is required"
                else ->
                    "MCP integration config: 'command' and 'url' are mutually exclusive — set only one"
            }
        }

        if (hasCommand) validateStdio(config) else validateHttp(config)
        validateTimeouts(config)

        return config
    }

    private fun validateStdio(config: McpServerConfig) {
        config.args.forEachIndexed { i, arg ->
            require(arg.isNotBlank()) { "MCP integration config: args[$i] must not be blank" }
        }
    }

    private fun validateHttp(config: McpServerConfig) {
        if (config.authToken != null) {
            require(config.authToken.isNotBlank()) {
                "MCP integration config: 'authToken' must not be blank when present"
            }
        }
    }

    private fun validateTimeouts(config: McpServerConfig) {
        require(config.timeoutSeconds > 0) {
            "MCP integration config: 'timeoutSeconds' must be positive, got ${config.timeoutSeconds}"
        }
        require(config.toolCallTimeoutSeconds > 0) {
            "MCP integration config: 'toolCallTimeoutSeconds' must be positive, got ${config.toolCallTimeoutSeconds}"
        }
        require(config.idleTimeoutMinutes > 0) {
            "MCP integration config: 'idleTimeoutMinutes' must be positive, got ${config.idleTimeoutMinutes}"
        }
    }
}
