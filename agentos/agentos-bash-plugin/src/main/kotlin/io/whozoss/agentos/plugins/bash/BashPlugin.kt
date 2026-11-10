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
 *
 * When the integration is bound to an Auth Setting, the context carries a credential provider. It is wrapped
 * in one [BashRunAuth] shared by all the tools built here and is **not invoked** by [provideTools]: it may
 * block on an OAuth authorization, which must only be asked of a user whose run actually calls a tool of
 * this integration. The secret then reaches each command as an environment variable named after the Auth
 * Setting (`$GITHUB_TOKEN` for a setting named `GITHUB_TOKEN`, plus `$<NAME>_USERNAME` for basic auth), see
 * [BashAuthVariables] and [BashTool]. The name comes from [ToolContext.authSettingName] and is handed to the
 * [BashRunAuth], which refuses a name that is not a safe variable name.
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

        val auth = context?.credentialProvider
            ?.let { BashRunAuth(configName, context.authSettingName, it) }
            ?: BashRunAuth.NONE

        return integrationConfig.tools.map { toolConfig ->
            BashTool(
                toolConfig = toolConfig,
                integrationConfig = integrationConfig,
                configName = configName,
                auth = auth,
            )
        }
    }

    companion object : KLogging() {
        // $$ string: the descriptions below mention shell variables ($GITHUB_TOKEN), which must stay literal.
        private val CONFIG_SCHEMA: JsonNode = jacksonObjectMapper().readTree(
            $$"""
            {
                "type": "object",
                "title": "Bash Integration Configuration",
                "description": "Exposes configured bash commands as individual tools for the LLM. When an Auth Setting is bound to the integration (authSettingName), its secret is available to every command as an environment variable named after the Auth Setting: with authSettingName GITHUB_TOKEN, commands use $GITHUB_TOKEN (the OAuth access token, bearer token or API key; for a basic-auth setting the password), and a basic-auth setting also sets $GITHUB_TOKEN_USERNAME. The Auth Setting name must be a valid environment variable name (letters, digits and underscores, not starting with a digit) and not a reserved one (PATH, HOME, BASH_ENV, LD_*, ...), otherwise the command is not run. The secret is resolved for the user running the case and never written in the command text, e.g. curl -H \"Authorization: Bearer $GITHUB_TOKEN\" https://api.example.com. The secret is masked as *** in the output returned to the LLM, on a best-effort basis only. If the bound Auth Setting yields no credential the command is not run.",
                "properties": {
                    "workingDirectory": {
                        "type": "string",
                        "title": "Working Directory",
                        "description": "Absolute path to the base directory from which all commands run. Write {{CASE_EXCHANGE}} to run in the current case's own directory, or {{NAMESPACE_EXCHANGE}} for the namespace shared one, which asks the user running the agent for Namespace WRITE. In a Git workspace the worktree is {{CASE_EXCHANGE}}/repo. A token this run cannot resolve removes this integration from the run."
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
                                    "description": "Bash command to execute. Use the literal token PARAMETERS as a placeholder where the LLM input will be substituted. If the entire command is PARAMETERS, the LLM provides the full bash command (raw mode). When an Auth Setting is bound to the integration, reference its secret as the environment variable named after it, e.g. curl -H \"Authorization: Bearer $GITHUB_TOKEN\" ... for authSettingName GITHUB_TOKEN (use double quotes so the shell expands it)."
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
