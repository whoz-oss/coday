package io.whozoss.agentos.plugins.ktlint

import com.fasterxml.jackson.databind.JsonNode
import com.fasterxml.jackson.module.kotlin.jacksonObjectMapper
import io.whozoss.agentos.sdk.tool.StandardTool
import io.whozoss.agentos.sdk.tool.ToolContext
import io.whozoss.agentos.sdk.tool.ToolPlugin
import mu.KLogging
import org.pf4j.Extension
import java.io.IOException
import java.nio.file.Files
import java.nio.file.Path

/**
 * Tool provider for the KTLINT integration.
 *
 * Exposes two tools sharing one [BoundedKtlintRun] on the configured `projectDirectory`:
 * [KtlintCheckTool] (`ktlint_check`) and [KtlintFormatTool] (`ktlint_format`).
 *
 * Config is validated by [KtlintConfigParser] on every [provideTools] call. A missing or invalid config,
 * or a `projectDirectory` that is not an existing directory, logs and returns an empty list rather than
 * crashing the service.
 */
@Extension
class KtlintToolProvider : ToolPlugin {
    override val integrationType: String = INTEGRATION_TYPE

    override val configSchema: JsonNode = CONFIG_SCHEMA

    override fun provideTools(
        config: JsonNode?,
        configName: String?,
        context: ToolContext?,
    ): List<StandardTool<*>> =
        when (val projectRoot = resolveProjectRoot(config = config, configName = configName)) {
            null -> emptyList()
            else -> toolsOn(projectRoot = projectRoot, configName = configName)
        }

    /** The check and format tools sharing one bounded run on [projectRoot]. */
    private fun toolsOn(
        projectRoot: Path,
        configName: String?,
    ): List<StandardTool<*>> {
        val bounded = BoundedKtlintRun(projectRoot = projectRoot)
        return listOf(
            KtlintCheckTool(bounded = bounded, configName = configName),
            KtlintFormatTool(bounded = bounded, configName = configName),
        )
    }

    /** The real project root of [config], or null (the reason is logged) when there is no usable one. */
    private fun resolveProjectRoot(
        config: JsonNode?,
        configName: String?,
    ): Path? =
        when {
            config == null || config.isNull -> {
                logger.warn { "KTLINT integration '$configName': no config provided, no tools registered" }
                null
            }

            else -> {
                try {
                    realProjectRoot(config = config)
                } catch (e: IllegalArgumentException) {
                    logger.error { "KTLINT integration '$configName': invalid config — ${e.message}" }
                    null
                } catch (e: IOException) {
                    logger.error { "KTLINT integration '$configName': projectDirectory cannot be resolved — ${e.message}" }
                    null
                }
            }
        }

    private fun realProjectRoot(config: JsonNode): Path {
        val integrationConfig = KtlintConfigParser.parse(config = config)
        val root = Path.of(integrationConfig.projectDirectory).toRealPath()
        require(Files.isDirectory(root)) { "'projectDirectory' is not a directory: ${integrationConfig.projectDirectory}" }
        return root
    }

    companion object : KLogging() {
        const val INTEGRATION_TYPE = "KTLINT"

        private val CONFIG_SCHEMA: JsonNode =
            jacksonObjectMapper().readTree(
                """
                {
                    "type": "object",
                    "title": "ktlint Integration Configuration",
                    "description": "Exposes ktlint (embedded engine, standard ruleset) as tools for the LLM: check and autocorrect the Kotlin style of files inside the configured project.",
                    "properties": {
                        "projectDirectory": {
                            "type": "string",
                            "title": "Project Directory",
                            "description": "Absolute path of the project whose Kotlin files the tools may check and format; no file outside it can be reached. Write {{CASE_EXCHANGE}} to use the current case's own directory; in a Git workspace the worktree is {{CASE_EXCHANGE}}/repo. In a filesystem integration config, {{NAMESPACE_CONFIG_PATH}} resolves to the namespace config path (e.g. {{NAMESPACE_CONFIG_PATH}}/../..)."
                        }
                    },
                    "required": ["projectDirectory"],
                    "additionalProperties": false
                }
                """.trimIndent(),
            )
    }
}
