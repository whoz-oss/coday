package io.whozoss.agentos.plugins.bash

import com.fasterxml.jackson.databind.JsonNode
import com.fasterxml.jackson.module.kotlin.jacksonObjectMapper
import io.whozoss.agentos.sdk.tool.StandardTool
import io.whozoss.agentos.sdk.tool.ToolContext
import io.whozoss.agentos.sdk.tool.ToolPlugin
import mu.KLogging
import org.pf4j.Extension
import org.pf4j.Plugin

class BashPlugin : Plugin() {
    override fun start() {
        logger.info { "Bash Plugin started!" }
    }

    override fun stop() {
        logger.info { "Bash Plugin stopped!" }
    }

    companion object : KLogging()
}

/**
 * Tool provider for the BASH integration.
 *
 * Each entry in the config's `tools` array becomes a distinct [BashTool] registered
 * with the AgentOS tool registry. The LLM sees each tool independently with its own
 * name, description, and input schema.
 *
 * Config is validated by [BashConfigParser] on every [provideTools] call. Invalid
 * config logs an error and returns an empty list rather than crashing the service.
 */
@Extension
class BashToolProvider : ToolPlugin {

    override val integrationType: String = "BASH"

    override val configSchema: JsonNode = CONFIG_SCHEMA

    override fun provideTools(config: JsonNode?, configName: String?, context: ToolContext?): List<StandardTool<*>> {
        if (config == null || config.isNull) {
            logger.warn { "BASH integration '$configName': no config provided, no tools registered" }
            return emptyList()
        }

        val integrationConfig = try {
            BashConfigParser.parse(config)
        } catch (e: IllegalArgumentException) {
            logger.error { "BASH integration '$configName': invalid config — ${e.message}" }
            return emptyList()
        }

        if (integrationConfig.tools.isEmpty()) {
            logger.warn { "BASH integration '$configName': no tools defined in config" }
            return emptyList()
        }

        return integrationConfig.tools.map { toolConfig ->
            BashTool(
                toolConfig = toolConfig,
                integrationConfig = integrationConfig,
                configName = configName,
            )
        }
    }

    companion object : KLogging() {
        private val CONFIG_SCHEMA: JsonNode = jacksonObjectMapper().readTree(
            """
            {
                "type": "object",
                "title": "Bash Integration Configuration",
                "description": "Exposes configured bash commands as individual tools for the LLM.",
                "properties": {
                    "workingDirectory": {
                        "type": "string",
                        "title": "Working Directory",
                        "description": "Absolute path to the base directory from which all commands run."
                    },
                    "defaultTimeoutSeconds": {
                        "type": "integer",
                        "title": "Default Timeout (seconds)",
                        "description": "Execution timeout applied to all tools unless overridden per tool.",
                        "default": 30,
                        "minimum": 1
                    },
                    "tools": {
                        "type": "array",
                        "title": "Tools",
                        "description": "List of bash commands to expose as tools.",
                        "items": {
                            "type": "object",
                            "properties": {
                                "name": {
                                    "type": "string",
                                    "title": "Tool Name",
                                    "description": "Unique identifier for this tool within the integration."
                                },
                                "description": {
                                    "type": "string",
                                    "title": "Description",
                                    "description": "Explanation sent to the LLM describing when and how to use this tool."
                                },
                                "command": {
                                    "type": "string",
                                    "title": "Command",
                                    "description": "Bash command to execute. Use the literal token PARAMETERS as a placeholder where the LLM input will be substituted. If the entire command is PARAMETERS, the LLM provides the full bash command (raw mode)."
                                },
                                "parametersDescription": {
                                    "type": "string",
                                    "title": "Parameters Description",
                                    "description": "Required when the command contains PARAMETERS. Describes to the LLM what value it should supply."
                                },
                                "path": {
                                    "type": "string",
                                    "title": "Subdirectory",
                                    "description": "Optional path relative to workingDirectory. The command runs from workingDirectory/path when set."
                                },
                                "timeoutSeconds": {
                                    "type": "integer",
                                    "title": "Timeout (seconds)",
                                    "description": "Overrides the integration-level defaultTimeoutSeconds for this specific tool.",
                                    "minimum": 1
                                }
                            },
                            "required": ["name", "description", "command"],
                            "additionalProperties": false
                        }
                    }
                },
                "required": ["workingDirectory"],
                "additionalProperties": false
            }
            """.trimIndent(),
        )
    }
}
