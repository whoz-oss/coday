package io.whozoss.agentos.plugins.ktlint

import com.fasterxml.jackson.databind.JsonNode
import java.io.File

/**
 * Configuration of the KTLINT integration.
 *
 * @property projectDirectory Absolute path of the project whose Kotlin files the tools may check and format.
 */
data class KtlintIntegrationConfig(
    val projectDirectory: String,
)

/**
 * Parses and validates a [JsonNode] config into a [KtlintIntegrationConfig].
 *
 * Validation rules:
 * - `projectDirectory` must be present, non-blank, and an absolute path.
 *
 * Throws [IllegalArgumentException] with a descriptive message on any violation.
 */
object KtlintConfigParser {
    /**
     * @throws IllegalArgumentException when `projectDirectory` is missing, blank or not an absolute path.
     */
    fun parse(config: JsonNode): KtlintIntegrationConfig {
        val projectDirectory =
            config
                .get("projectDirectory")
                ?.takeIf { !it.isNull }
                ?.asText()
                ?.trim()
        require(!projectDirectory.isNullOrBlank()) {
            "KTLINT integration config: 'projectDirectory' is required and must not be blank"
        }
        require(File(projectDirectory).isAbsolute) {
            "KTLINT integration config: 'projectDirectory' must be an absolute path, got: $projectDirectory"
        }
        return KtlintIntegrationConfig(projectDirectory = projectDirectory)
    }
}
