package io.whozoss.agentos.plugins.kotlin

import com.fasterxml.jackson.databind.JsonNode
import java.io.File

/**
 * Configuration of the KOTLIN integration.
 *
 * @property projectDirectory Absolute path of the Kotlin project the tools work in.
 */
internal data class KotlinIntegrationConfig(
    val projectDirectory: String,
)

/**
 * Parses and validates a [JsonNode] config into a [KotlinIntegrationConfig].
 *
 * Validation rules:
 * - `projectDirectory` must be present, non-blank, and an absolute path.
 *
 * The language server binary is deliberately not a parameter: see [ENV_SERVER_BINARY].
 */
internal object KotlinConfigParser {
    const val PROJECT_DIRECTORY = "projectDirectory"

    /**
     * @throws IllegalArgumentException when `projectDirectory` is missing, blank or not an absolute path.
     */
    fun parse(config: JsonNode): KotlinIntegrationConfig {
        val projectDirectory =
            config
                .get(PROJECT_DIRECTORY)
                ?.takeIf { !it.isNull }
                ?.asText()
                ?.trim()
        require(!projectDirectory.isNullOrBlank()) {
            "KOTLIN integration config: '$PROJECT_DIRECTORY' is required and must not be blank"
        }
        require(File(projectDirectory).isAbsolute) {
            "KOTLIN integration config: '$PROJECT_DIRECTORY' must be an absolute path, got: $projectDirectory"
        }
        return KotlinIntegrationConfig(projectDirectory = projectDirectory)
    }
}
