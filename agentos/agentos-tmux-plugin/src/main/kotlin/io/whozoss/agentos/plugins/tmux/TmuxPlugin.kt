package io.whozoss.agentos.plugins.tmux

import com.fasterxml.jackson.databind.JsonNode
import com.fasterxml.jackson.module.kotlin.jacksonObjectMapper
import io.whozoss.agentos.sdk.tool.StandardTool
import io.whozoss.agentos.sdk.tool.ToolContext
import io.whozoss.agentos.sdk.tool.ToolPlugin
import mu.KLogging
import org.pf4j.Extension
import org.pf4j.Plugin

class TmuxPlugin : Plugin() {
    override fun start() {
        logger.info { "Tmux Plugin started!" }
    }

    override fun stop() {
        logger.info { "Tmux Plugin stopped!" }
    }

    companion object : KLogging()
}

/**
 * Tool provider for the Tmux integration.
 *
 * Declares [configSchema] so the service exposes it via IntegrationTypeRegistry,
 * and instantiates [TmuxTool] with the optional [workingDirectory] drawn from
 * the persisted IntegrationConfig parameters (or null if no config is stored,
 * in which case the tool falls back to the user's home directory).
 */
@Extension
class TmuxToolProvider : ToolPlugin, io.whozoss.agentos.sdk.tool.WorkspaceToolLifecycle {
    override fun releaseWorkspace(workspaceId: String, directory: String) {
        require(workspaceId.matches(Regex("[a-fA-F0-9-]+")))
        val process = try { ProcessBuilder("tmux", "-L", "agentos-$workspaceId", "kill-server").redirectErrorStream(true).start() }
        catch (e: java.io.IOException) {
            if (e.message?.contains("error=2") == true) return // tmux was never installed on this host
            throw e
        }
        check(process.waitFor(10, java.util.concurrent.TimeUnit.SECONDS)) { "Could not stop workspace tmux server" }
    }

    override val integrationType: String = "TMUX"

    override val configSchema: JsonNode = CONFIG_SCHEMA

    override fun provideTools(
        config: JsonNode?,
        configName: String?,
        context: ToolContext?,
    ): List<StandardTool<*>> {
        val workingDirectory =
            config
                ?.get("workingDirectory")
                ?.asText()
                ?.takeIf { it.isNotBlank() }
        return listOf(
            TmuxTool(workingDirectory = workingDirectory, configName = configName, socketName = config?.get("socketName")?.asText()),
            WaitTool(configName = configName),
        )
    }

    companion object : KLogging() {
        private val CONFIG_SCHEMA: JsonNode =
            jacksonObjectMapper().readTree(
                """
                {
                    "type": "object",
                    "title": "Tmux Configuration",
                    "description": "Configuration for the Tmux integration.",
                    "properties": {
                    "useCaseExchangeDirectory": {
                        "type": "boolean",
                        "title": "Use case workspace directory",
                        "description": "Run in the shared Case Exchange when this case has a Git workspace. Otherwise use the configured directory.",
                        "default": true
                    },
                        "workingDirectory": {
                            "type": "string",
                            "title": "Working Directory",
                            "description": "Absolute path used as the working directory when creating new tmux sessions. Defaults to the user home directory if omitted."
                        }
                    },
                    "additionalProperties": false
                }
                """.trimIndent(),
            )
    }
}
