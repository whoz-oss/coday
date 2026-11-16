package io.whozoss.agentos.plugins.gradle

import com.fasterxml.jackson.databind.JsonNode
import java.io.File

/**
 * Configuration of the GRADLE integration.
 *
 * @property projectDirectory Absolute path of the Gradle project the tools run in.
 */
data class GradleIntegrationConfig(
    val projectDirectory: String,
)

/**
 * Parses and validates a [JsonNode] config into a [GradleIntegrationConfig].
 *
 * Validation rules:
 * - `projectDirectory` must be present, non-blank, and an absolute path.
 *
 * Throws [IllegalArgumentException] with a descriptive message on any violation.
 */
object GradleConfigParser {
    fun parse(config: JsonNode): GradleIntegrationConfig {
        val projectDirectory = config.get("projectDirectory")?.takeIf { !it.isNull }?.asText()?.trim()
        require(!projectDirectory.isNullOrBlank()) {
            "GRADLE integration config: 'projectDirectory' is required and must not be blank"
        }
        require(File(projectDirectory).isAbsolute) {
            "GRADLE integration config: 'projectDirectory' must be an absolute path, got: $projectDirectory"
        }
        return GradleIntegrationConfig(projectDirectory)
    }
}
