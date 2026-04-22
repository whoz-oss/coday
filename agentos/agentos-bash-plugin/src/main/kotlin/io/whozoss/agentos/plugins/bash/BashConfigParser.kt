package io.whozoss.agentos.plugins.bash

import com.fasterxml.jackson.databind.JsonNode
import java.io.File

/**
 * Parses and validates a [JsonNode] config into a [BashIntegrationConfig].
 *
 * Validation rules:
 * - [BashIntegrationConfig.workingDirectory] must be present, non-blank, and an absolute path.
 * - Each tool must have a non-blank [BashToolConfig.name] and [BashToolConfig.description].
 * - Each tool must have a non-blank [BashToolConfig.command].
 * - Tool names must be unique within the integration.
 * - If a tool command contains [PARAMETERS_PLACEHOLDER], [BashToolConfig.parametersDescription]
 *   must be provided and non-blank.
 * - If [BashToolConfig.parametersDescription] is set but the command does not contain
 *   [PARAMETERS_PLACEHOLDER], that is flagged as a configuration mistake.
 * - [BashToolConfig.path], when set, must not escape [BashIntegrationConfig.workingDirectory]
 *   (no path traversal).
 * - Timeouts, when provided, must be positive.
 *
 * Throws [IllegalArgumentException] with a descriptive message on any violation.
 */
object BashConfigParser {

    fun parse(config: JsonNode): BashIntegrationConfig {
        val workingDirectory = config.get("workingDirectory")?.asText()?.trim()
        require(!workingDirectory.isNullOrBlank()) {
            "BASH integration config: 'workingDirectory' is required and must not be blank"
        }
        require(File(workingDirectory).isAbsolute) {
            "BASH integration config: 'workingDirectory' must be an absolute path, got: $workingDirectory"
        }

        val defaultTimeout = config.get("defaultTimeoutSeconds")?.asLong()?.also { timeout ->
            require(timeout > 0) {
                "BASH integration config: 'defaultTimeoutSeconds' must be positive, got $timeout"
            }
        } ?: DEFAULT_TIMEOUT_SECONDS

        val toolsNode = config.get("tools")
        val tools = when {
            toolsNode == null || toolsNode.isNull || !toolsNode.isArray -> emptyList()
            else -> toolsNode.mapIndexed { index, toolNode ->
                parseTool(toolNode, index, workingDirectory)
            }
        }

        val duplicates = tools.map { it.name }.groupBy { it }.filter { it.value.size > 1 }.keys
        require(duplicates.isEmpty()) {
            "BASH integration config: duplicate tool names: ${duplicates.joinToString()}"
        }

        return BashIntegrationConfig(
            workingDirectory = workingDirectory,
            defaultTimeoutSeconds = defaultTimeout,
            tools = tools,
        )
    }

    private fun parseTool(node: JsonNode, index: Int, workingDirectory: String): BashToolConfig {
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

        val path = node.get("path")?.takeIf { !it.isNull }?.asText()?.trim()?.takeIf { it.isNotBlank() }
        if (path != null) {
            validatePath(name, path, workingDirectory)
        }

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

    /**
     * Validates that [path] does not escape [workingDirectory] via path traversal.
     *
     * We canonicalize the resolved path and check that it starts with the
     * canonical working directory, which prevents `../../etc`-style escapes
     * regardless of symlinks on the filesystem.
     *
     * Note: the working directory and subdirectory do not need to exist at
     * config-parse time (the service may be configured before the directory
     * is mounted). We therefore use string-based normalization only.
     */
    private fun validatePath(toolName: String, path: String, workingDirectory: String) {
        require(!File(path).isAbsolute) {
            "BASH integration config: tool '$toolName' path must be relative, got: $path"
        }
        val normalized = File(workingDirectory, path).canonicalPath
        val base = File(workingDirectory).canonicalPath
        require(normalized.startsWith(base + File.separator) || normalized == base) {
            "BASH integration config: tool '$toolName' path escapes workingDirectory: $path"
        }
    }
}
