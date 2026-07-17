package io.whozoss.agentos.plugins.file

import com.fasterxml.jackson.databind.JsonNode
import com.fasterxml.jackson.module.kotlin.jacksonObjectMapper
import io.whozoss.agentos.plugins.file.tools.EditFilesTool
import io.whozoss.agentos.plugins.file.tools.ListFilesTool
import io.whozoss.agentos.plugins.file.tools.MoveFileTool
import io.whozoss.agentos.plugins.file.tools.ReadAsImageTool
import io.whozoss.agentos.plugins.file.tools.ReadFileTool
import io.whozoss.agentos.plugins.file.tools.ReadSpreadsheetTool
import io.whozoss.agentos.plugins.file.tools.RemoveFileTool
import io.whozoss.agentos.plugins.file.tools.SearchFilesTool
import io.whozoss.agentos.sdk.tool.StandardTool
import io.whozoss.agentos.sdk.tool.ToolContext
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
 * When [readOnly] is true, only the read tools (list, read, readAsImage,
 * readSpreadsheet, search) are provided. Write tools (edit, remove, move) are not
 * included at all, so the agent is never aware of their existence.
 */
@Extension
class FileToolProvider : ToolPlugin {

    override val integrationType: String = "FILE_ACCESS"

    override val configSchema: JsonNode = CONFIG_SCHEMA

    override fun provideTools(config: JsonNode?, configName: String?, context: ToolContext?): List<StandardTool<*>> {
        if (config == null) return emptyList()

        val rootPath = Path.of(config.get("rootPath")?.asText() ?: error("rootPath is required"))
        val readOnly = config.get("readOnly")?.asBoolean() ?: false
        val readMaxSizeMb = config.get("readMaxSizeMb")?.asLong() ?: 10
        val extraDenyPatterns = config.get("extraDenyPatterns")
            ?.takeIf { it.isArray }
            ?.map { it.asText() }
            ?: emptyList()

        val readMaxSizeBytes = readMaxSizeMb * 1024 * 1024
        val denyPatterns = SensitiveFilePatterns.DEFAULT_PATTERNS + extraDenyPatterns

        val readTools = listOf(
            ListFilesTool(rootPath, configName, denyPatterns),
            ReadFileTool(rootPath, configName, readMaxSizeBytes, denyPatterns),
            ReadAsImageTool(rootPath, configName, readMaxSizeBytes, denyPatterns),
            ReadSpreadsheetTool(rootPath, configName, readMaxSizeBytes, denyPatterns),
            SearchFilesTool(rootPath, configName, denyPatterns),
        )

        val writeTools = listOf(
            EditFilesTool(rootPath, configName, denyPatterns),
            RemoveFileTool(rootPath, configName, denyPatterns),
            MoveFileTool(rootPath, configName, denyPatterns),
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
                        "description": "If true, only list/read/search tools are provided.",
                        "default": false
                    },
                    "readMaxSizeMb": {
                        "type": "integer",
                        "title": "Read Max Size (MB)",
                        "description": "Maximum file size in megabytes that the read tools will read. Default is 10 MB.",
                        "default": 10
                    },
                    "extraDenyPatterns": {
                        "type": "array",
                        "title": "Extra Deny Patterns",
                        "description": "Additional glob patterns to block on top of the built-in sensitive file list (.env, *.key, *.pem, etc.). Cannot remove built-in patterns. A JSON null value is treated as an empty list.",
                        "items": { "type": "string" },
                        "default": []
                    }
                },
                "required": ["rootPath"],
                "additionalProperties": false
            }
            """.trimIndent(),
        )
    }
}
