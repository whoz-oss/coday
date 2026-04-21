package io.whozoss.agentos.plugins.bash

import com.fasterxml.jackson.databind.JsonNode

/**
 * Parses and validates a [JsonNode] config into a [BashIntegrationConfig].
 *
 * Validation rules:
 * - [BashIntegrationConfig.workingDirectory] must be present and non-blank.
 * - Each tool must have a non-blank [BashToolConfig.name] and [BashToolConfig.description].
 * - Each tool must have a non-blank [BashToolConfig.command].
 * - Tool names must be unique within the integration.
 * - If a tool command contains [PARAMETERS_PLACEHOLDER], [BashToolConfig.parametersDescription]
 *   must be provided and non-blank.
 * - If [BashToolConfig.parametersDescription] is set but the command does not contain
 *   [PARAMETERS_PLACEHOLDER], that is flagged as a configuration mistake.
 * - Timeouts, when provided, must be positive.
 *
 * Throws [BashConfigException] with a descriptive message on any violation.
 */
object BashConfigParser {

    fun parse(config: JsonNode): BashIntegrationConfig {
        val workingDirectory = config.get("workingDirectory")?.asText()?.trim()
        require(!workingDirectory.isNullOrBlank()) {
            "BASH integration config: 'workingDirectory' is required and must not be blank"
        }

        val defaultTimeout = config.get("defaultTimeoutSeconds")?.asLong()?.also { timeout ->
            require(timeout > 0) {
                "BASH integration config: 'defaultTimeoutSeconds' must be positive, got $timeout"
            }
        } ?: 30L

        val toolsNode = config.get("tools")
        val tools = if (toolsNode == null || toolsNode.isNull || !toolsNode.isArray) {
            emptyList()
        } else {
            toolsNode.mapIndexed { index, toolNode -> parseTool(toolNode, index) }
        }

        val names = tools.map { it.name }
        val duplicates = names.groupBy { it }.filter { it.value.size > 1 }.keys
        require(duplicates.isEmpty()) {
            "BASH integration config: duplicate tool names: ${duplicates.joinToString()}"
        }

        return BashIntegrationConfig(
            workingDirectory = workingDirectory,
            defaultTimeoutSeconds = defaultTimeout,
            tools = tools,
        )
    }

    private fun parseTool(node: JsonNode, index: Int): BashToolConfig {
        val name = node.get("name")?.asText()?.trim()
        require(!name.isNullOrBlank()) {
            "BASH integration config: tool[$index].name is required and must not be blank"
        }

        val description = node.get("description")?.asText()?.trim()
        require(!description.isNullOrBlank()) {
            "BASH integration config: tool '$name' description is required and must not be blank"
        }

        val command = node.get("command")?.asText()?.trim()
        require(!command.isNullOrBlank()) {
            "BASH integration config: tool '$name' command is required and must not be blank"
        }

        val hasPlaceholder = command.contains(PARAMETERS_PLACEHOLDER)
        val parametersDescription = node.get("parametersDescription")?.asText()?.trim()?.takeIf { it.isNotBlank() }

        require(!(hasPlaceholder && parametersDescription == null)) {
            "BASH integration config: tool '$name' command contains '$PARAMETERS_PLACEHOLDER' " +
                "but 'parametersDescription' is missing or blank"
        }
        require(!(parametersDescription != null && !hasPlaceholder)) {
            "BASH integration config: tool '$name' has 'parametersDescription' " +
                "but command does not contain '$PARAMETERS_PLACEHOLDER'"
        }

        val path = node.get("path")?.asText()?.trim()?.takeIf { it.isNotBlank() }

        val timeoutSeconds = node.get("timeoutSeconds")?.takeIf { !it.isNull }?.asLong()?.also { timeout ->
            require(timeout > 0) {
                "BASH integration config: tool '$name' timeoutSeconds must be positive, got $timeout"
            }
        }

        return BashToolConfig(
            name = name,
            description = description,
            command = command,
            parametersDescription = parametersDescription,
            path = path,
            timeoutSeconds = timeoutSeconds,
        )
    }
}

class BashConfigException(message: String) : IllegalArgumentException(message)
