package io.whozoss.agentos.plugins.datetime

import com.fasterxml.jackson.databind.JsonNode
import com.fasterxml.jackson.module.kotlin.jacksonObjectMapper
import io.whozoss.agentos.sdk.tool.StandardTool
import io.whozoss.agentos.sdk.tool.ToolContext
import io.whozoss.agentos.sdk.tool.ToolPlugin
import mu.KLogging
import org.pf4j.Extension
import org.pf4j.Plugin

class DateTimePlugin : Plugin() {
    override fun start() {
        logger.info { "DateTime Plugin started!" }
    }

    override fun stop() {
        logger.info { "DateTime Plugin stopped!" }
    }

    companion object : KLogging()
}

/**
 * Tool provider for the DateTime integration.
 *
 * Declares [configSchema] so the service exposes it via IntegrationTypeRegistry,
 * and instantiates [GetCurrentDateTimeTool] with the defaultTimezone drawn from
 * the persisted IntegrationConfig parameters (or "UTC" if no config is stored).
 */
@Extension
class DateTimeToolProvider : ToolPlugin {
    override val integrationType: String = "DATETIME"

    override val configSchema: JsonNode = CONFIG_SCHEMA

    override fun provideTools(
        config: JsonNode?,
        configName: String?,
        context: ToolContext?,
    ): List<StandardTool<*>> {
        val defaultTimezone =
            config
                ?.get("defaultTimezone")
                ?.asText()
                ?.takeIf { it.isNotBlank() }
                ?: "UTC"
        return listOf(GetCurrentDateTimeTool(defaultTimezone = defaultTimezone, configName = configName))
    }

    companion object : KLogging() {
        private val CONFIG_SCHEMA: JsonNode =
            jacksonObjectMapper().readTree(
                """
                {
                    "type": "object",
                    "title": "DateTime Configuration",
                    "description": "Configuration for the DateTime integration.",
                    "properties": {
                        "defaultTimezone": {
                            "type": "string",
                            "title": "Default Timezone",
                            "description": "IANA timezone used when the AI does not specify one explicitly. Examples: 'Europe/Paris', 'America/New_York', 'UTC'.",
                            "default": "UTC"
                        }
                    },
                    "additionalProperties": false
                }
                """.trimIndent(),
            )
    }
}
