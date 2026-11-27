package io.whozoss.agentos.plugins.kotlin

import com.fasterxml.jackson.databind.JsonNode
import java.io.File

/**
 * Configuration of the KOTLIN integration.
 *
 * @property projectDirectory Absolute path of the Kotlin project the tools work in.
 * @property serverBinary Absolute path of the Kotlin language server executable, or null to use the environment
 *   variable [ENV_SERVER_BINARY] of the service, else the user install.
 */
internal data class KotlinIntegrationConfig(
    val projectDirectory: String,
    val serverBinary: String? = null,
)

/**
 * Parses and validates a [JsonNode] config into a [KotlinIntegrationConfig].
 *
 * Validation rules:
 * - `projectDirectory` must be present, non-blank, and an absolute path.
 * - `serverBinary` is optional (absent, JSON null and blank all mean unset); when set it must be an absolute path.
 *   That it exists and is executable is checked when a tool runs, and that it is not inside the project by
 *   [KotlinToolProvider], which knows the real project root.
 */
internal object KotlinConfigParser {
    const val PROJECT_DIRECTORY = "projectDirectory"
    const val SERVER_BINARY = "serverBinary"

    /**
     * @throws IllegalArgumentException when `projectDirectory` is missing, blank or not an absolute path, or when
     *   `serverBinary` is set and not an absolute path.
     */
    fun parse(config: JsonNode): KotlinIntegrationConfig {
        val projectDirectory = textOf(config = config, key = PROJECT_DIRECTORY)
        require(!projectDirectory.isNullOrBlank()) {
            "KOTLIN integration config: '$PROJECT_DIRECTORY' is required and must not be blank"
        }
        require(File(projectDirectory).isAbsolute) {
            "KOTLIN integration config: '$PROJECT_DIRECTORY' must be an absolute path, got: $projectDirectory"
        }
        val serverBinary = textOf(config = config, key = SERVER_BINARY)?.takeIf { it.isNotBlank() }
        require(serverBinary == null || File(serverBinary).isAbsolute) {
            "KOTLIN integration config: '$SERVER_BINARY' must be an absolute path, got: $serverBinary"
        }
        return KotlinIntegrationConfig(projectDirectory = projectDirectory, serverBinary = serverBinary)
    }

    private fun textOf(
        config: JsonNode,
        key: String,
    ): String? =
        config
            .get(key)
            ?.takeIf { !it.isNull }
            ?.asText()
            ?.trim()
}
