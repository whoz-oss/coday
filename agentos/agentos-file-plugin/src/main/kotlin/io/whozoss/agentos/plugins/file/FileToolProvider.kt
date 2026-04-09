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
 * and instantiates file tools with the rootPath drawn from the persisted
 * IntegrationConfig parameters.
 *
 * When [readOnly] is true, only the read tools (list, read, search) are provided.
 * Write tools (edit, remove, move) are not included at all, so the agent
 * is never aware of their existence.
 */
@Extension
class FileToolProvider : ToolPlugin {

    override val integrationType: String = "FILE_ACCESS"

    override val configSchema: JsonNode = CONFIG_SCHEMA

    override fun provideTools(config: JsonNode?, configName: String?): List<StandardTool<*>> {
        if (config == null) return emptyList()

        val rootPath = Path.of(
            config.get("rootPath")?.asText()
                ?: error("rootPath is required in FILE_ACCESS config"),
        )
        val readOnly = config.get("readOnly")?.asBoolean() ?: false

        val readTools = listOf(
            ListFilesTool(rootPath, configName),
            ReadFileTool(rootPath, configName),
            SearchFilesTool(rootPath, configName),
        )

        val writeTools = listOf(
            EditFilesTool(rootPath, configName),
            RemoveFileTool(rootPath, configName),
            MoveFileTool(rootPath, configName),
        )

        return if (readOnly) readTools else readTools + writeTools
    }

    companion object : KLogging() {
        private val CONFIG_SCHEMA: JsonNode = jacksonObjectMapper().readTree(
            """
            {
                "type": "object",
                "title": "File Access Configuration",
                "description": "Configuration for the File Access integration.",
                "properties": {
                    "rootPath": {
                        "type": "string",
                        "title": "Root Path",
                        "description": "Absolute path to the root directory to expose."
                    },
                    "readOnly": {
                        "type": "boolean",
                        "title": "Read Only",
                        "description": "If true, only list/read/search tools are provided. Write tools are not exposed at all.",
                        "default": false
                    }
                },
                "required": ["rootPath"],
                "additionalProperties": false
            }
            """.trimIndent(),
        )
    }
}
