package io.whozoss.agentos.plugins.file

import com.fasterxml.jackson.databind.JsonNode
import com.fasterxml.jackson.module.kotlin.jacksonObjectMapper
import io.whozoss.agentos.plugins.file.tools.EditFilesTool
import io.whozoss.agentos.plugins.file.tools.ListFilesTool
import io.whozoss.agentos.plugins.file.tools.MoveFileTool
import io.whozoss.agentos.plugins.file.tools.ReadFileTool
import io.whozoss.agentos.plugins.file.tools.RemoveFileTool
import io.whozoss.agentos.plugins.file.tools.SearchFilesTool
import io.whozoss.agentos.sdk.tool.StandardTool
import io.whozoss.agentos.sdk.tool.ToolPlugin
import mu.KLogging
import org.pf4j.Extension
import java.nio.file.Path

/**
 * Tool provider for the FILE_ACCESS integration.
 *
 * Declares [configSchema] so the service exposes it via IntegrationTypeRegistry,
 * and instantiates the 6 file tools with the projectRoot and readOnly drawn from
 * the persisted IntegrationConfig parameters.
 */
@Extension
class FileToolProvider : ToolPlugin {

    override val integrationType: String = "FILE_ACCESS"

    override val configSchema: JsonNode = CONFIG_SCHEMA

    override fun provideTools(config: JsonNode?, configName: String?): List<StandardTool<*>> {
        if (config == null) return emptyList()

        val projectRoot = Path.of(
            config.get("projectRoot")?.asText()
                ?: error("projectRoot is required in FILE_ACCESS config"),
        )
        val readOnly = config.get("readOnly")?.asBoolean() ?: false

        return listOf(
            ListFilesTool(projectRoot, configName),
            ReadFileTool(projectRoot, configName),
            SearchFilesTool(projectRoot, configName),
            EditFilesTool(projectRoot, configName, readOnly),
            RemoveFileTool(projectRoot, configName, readOnly),
            MoveFileTool(projectRoot, configName, readOnly),
        )
    }

    companion object : KLogging() {
        private val CONFIG_SCHEMA: JsonNode = jacksonObjectMapper().readTree(
            """
            {
                "type": "object",
                "title": "File Access Configuration",
                "description": "Configuration for the File Access integration.",
                "properties": {
                    "projectRoot": {
                        "type": "string",
                        "title": "Project Root",
                        "description": "Absolute path to the project root directory."
                    },
                    "readOnly": {
                        "type": "boolean",
                        "title": "Read Only",
                        "description": "If true, write operations (edit, remove, move) are disabled.",
                        "default": false
                    }
                },
                "required": ["projectRoot"],
                "additionalProperties": false
            }
            """.trimIndent(),
        )
    }
}
