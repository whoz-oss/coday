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
 * Config is validated by [KotlinConfigParser] on every [provideTools] call, on the configuration merged by name from the
 * platform, namespace and user layers: `projectDirectory` is therefore not required by the schema (a platform layer
 * only sets `serverBinary`), but a merged configuration without it registers no tool. A missing or invalid config, a
 * `projectDirectory` that is not an existing directory, or a `serverBinary` inside it, logs and returns an empty list
 * rather than crashing the service. The server binary is the `serverBinary` of the integration, else the environment
 * variable [ENV_SERVER_BINARY] of the service, else the user install; no server is started when the tools are built,
 * only when one of them runs.
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
        when (val resolved = resolveConfig(config = config, configName = configName)) {
            null -> {
                emptyList()
            }

            else -> {
                kotlinLspTools(
                    configName = configName,
                    run = KotlinLspRun(projectRoot = resolved.projectRoot, serverBinary = resolved.serverBinary),
                )
            }
        }

    /** What a valid configuration comes down to: the real project root and the explicit server binary, if any. */
    private data class ResolvedConfig(
        val projectRoot: Path,
        val serverBinary: String?,
    )

    /** The usable configuration of [config], or null (the reason is logged) when there is none. */
    private fun resolveConfig(
        config: JsonNode?,
        configName: String?,
    ): ResolvedConfig? =
        when {
            config == null || config.isNull -> {
                logger.warn { "KOTLIN integration '$configName': no config provided, no tools registered" }
                null
            }

            else -> {
                try {
                    validated(config = config)
                } catch (e: IllegalArgumentException) {
                    logger.error { "KOTLIN integration '$configName': invalid config — ${e.message}" }
                    null
                } catch (e: IOException) {
                    logger.error { "KOTLIN integration '$configName': projectDirectory cannot be resolved — ${e.message}" }
                    null
                }
            }
        }

    private fun validated(config: JsonNode): ResolvedConfig {
        val integrationConfig = KotlinConfigParser.parse(config = config)
        val root = Path.of(integrationConfig.projectDirectory).toRealPath()
        require(Files.isDirectory(root)) { "'projectDirectory' is not a directory: ${integrationConfig.projectDirectory}" }
        val serverBinary = integrationConfig.serverBinary
        require(serverBinary == null || !realOrNormalized(path = Path.of(serverBinary)).startsWith(root)) {
            "'${KotlinConfigParser.SERVER_BINARY}' must not be inside 'projectDirectory': agents can write there, " +
                "and the service would run what they wrote ($serverBinary)"
        }
        return ResolvedConfig(projectRoot = root, serverBinary = serverBinary)
    }

    /**
     * [path] with the symbolic links of its deepest existing ancestor resolved, so that a binary that does not exist
     * yet, or one reached through a link, is compared with the real project root.
     */
    private fun realOrNormalized(path: Path): Path {
        val normalized = path.normalize()
        val existing = generateSequence(normalized) { it.parent }.firstOrNull { Files.exists(it) }
        return existing?.let { it.toRealPath().resolve(it.relativize(normalized)) } ?: normalized
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
                    "description": "Exposes a Kotlin language server (LSP) as tools for the LLM: navigation, diagnostics, completion and refactoring of the Kotlin code of the configured project. The server binary is the serverBinary below, else the environment variable AGENTOS_KOTLIN_LSP_BINARY of the AgentOS service, else ~/kotlin-lsp.",
                    "properties": {
                        "projectDirectory": {
                            "type": "string",
                            "title": "Project Directory",
                            "description": "Absolute path of the Kotlin project the tools work in; no file outside it can be reached, and the rename tools write inside it. Set it on the namespace integration. Leave it empty on a platform-level integration that only provides the serverBinary: configurations of the same name are merged at run time, and no tool is built while the merged one has no projectDirectory. Write {{CASE_EXCHANGE}} to use the current case's own directory; in a Git workspace the worktree is {{CASE_EXCHANGE}}/repo. In a filesystem integration config, {{NAMESPACE_CONFIG_PATH}} resolves to the namespace config path (e.g. {{NAMESPACE_CONFIG_PATH}}/../..)."
                        },
                        "serverBinary": {
                            "type": "string",
                            "title": "Server Binary",
                            "description": "Optional. Absolute path of the Kotlin language server executable (intellij-server, over 1 GB, not shipped). Leave empty to use the environment variable AGENTOS_KOTLIN_LSP_BINARY of the service, else ~/kotlin-lsp/server/bin/intellij-server. The service runs this file with its own rights: it cannot be inside projectDirectory, and must not be writable by agents."
                        }
                    },
                    "additionalProperties": false
                }
                """.trimIndent(),
            )
    }
}
