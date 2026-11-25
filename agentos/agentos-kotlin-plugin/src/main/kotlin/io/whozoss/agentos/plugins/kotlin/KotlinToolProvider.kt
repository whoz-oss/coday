package io.whozoss.agentos.plugins.kotlin

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
 * Tool provider for the KOTLIN integration: a Kotlin language server (LSP) exposed as tools.
 *
 * Exposes 18 tools sharing one [KotlinLspRun] on the configured `projectDirectory` (navigation, search, refactoring,
 * editor tools; see [kotlinLspTools]). No file outside it is read or written, and never a sensitive file.
 *
 * Config is validated by [KotlinConfigParser] on every [provideTools] call. A missing or invalid config, or a
 * `projectDirectory` that is not an existing directory, logs and returns an empty list rather than crashing the
 * service. The server binary is chosen by whoever operates the service ([ENV_SERVER_BINARY]), and no server is
 * started when the tools are built, only when one of them runs.
 */
@Extension
class KotlinToolProvider : ToolPlugin {
    override val integrationType: String = INTEGRATION_TYPE

    override val configSchema: JsonNode = CONFIG_SCHEMA

    override fun provideTools(
        config: JsonNode?,
        configName: String?,
        context: ToolContext?,
    ): List<StandardTool<*>> =
        when (val projectRoot = resolveProjectRoot(config = config, configName = configName)) {
            null -> emptyList()
            else -> kotlinLspTools(configName = configName, run = KotlinLspRun(projectRoot = projectRoot))
        }

    /** The real project root of [config], or null (the reason is logged) when there is no usable one. */
    private fun resolveProjectRoot(
        config: JsonNode?,
        configName: String?,
    ): Path? =
        when {
            config == null || config.isNull -> {
                logger.warn { "KOTLIN integration '$configName': no config provided, no tools registered" }
                null
            }

            else -> {
                try {
                    realProjectRoot(config = config)
                } catch (e: IllegalArgumentException) {
                    logger.error { "KOTLIN integration '$configName': invalid config — ${e.message}" }
                    null
                } catch (e: IOException) {
                    logger.error { "KOTLIN integration '$configName': projectDirectory cannot be resolved — ${e.message}" }
                    null
                }
            }
        }

    private fun realProjectRoot(config: JsonNode): Path {
        val integrationConfig = KotlinConfigParser.parse(config = config)
        val root = Path.of(integrationConfig.projectDirectory).toRealPath()
        require(Files.isDirectory(root)) { "'projectDirectory' is not a directory: ${integrationConfig.projectDirectory}" }
        return root
    }

    companion object : KLogging() {
        /** Identifier of the integration type this provider serves, as persisted in integration configs. */
        const val INTEGRATION_TYPE = "KOTLIN"

        private val CONFIG_SCHEMA: JsonNode =
            jacksonObjectMapper().readTree(
                """
                {
                    "type": "object",
                    "title": "Kotlin Integration Configuration",
                    "description": "Exposes a Kotlin language server (LSP) as tools for the LLM: navigation, diagnostics, completion and refactoring of the Kotlin code of the configured project. The server binary is installed by whoever operates the service (environment variable AGENTOS_KOTLIN_LSP_BINARY, else ~/kotlin-lsp).",
                    "properties": {
                        "projectDirectory": {
                            "type": "string",
                            "title": "Project Directory",
                            "description": "Absolute path of the Kotlin project the tools work in; no file outside it can be reached, and the rename tools write inside it. Write {{CASE_EXCHANGE}} to use the current case's own directory; in a Git workspace the worktree is {{CASE_EXCHANGE}}/repo. In a filesystem integration config, {{NAMESPACE_CONFIG_PATH}} resolves to the namespace config path (e.g. {{NAMESPACE_CONFIG_PATH}}/../..)."
                        }
                    },
                    "required": ["projectDirectory"],
                    "additionalProperties": false
                }
                """.trimIndent(),
            )
    }
}
