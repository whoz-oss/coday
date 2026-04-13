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

        val fileConfig = objectMapper.treeToValue(config, FileAccessConfig::class.java)
        val rootPath = Path.of(fileConfig.rootPath)
        val denyPatterns = fileConfig.effectiveDenyPatterns

        // Warn when readMaxSizeMb is clamped
        if (fileConfig.readMaxSizeMb !in 1..50) {
            logger.warn {
                "readMaxSizeMb=${fileConfig.readMaxSizeMb} is outside [1, 50]; " +
                    "clamped to ${fileConfig.readMaxSizeMb.coerceIn(1, 50)} MB"
            }
        }

        val readTools = listOf(
            ListFilesTool(rootPath, configName, denyPatterns),
            ReadFileTool(rootPath, configName, fileConfig.readMaxSizeBytes, denyPatterns),
            SearchFilesTool(rootPath, configName, denyPatterns),
        )

        val writeTools = listOf(
            EditFilesTool(rootPath, configName, denyPatterns),
            RemoveFileTool(rootPath, configName, denyPatterns),
            MoveFileTool(rootPath, configName, denyPatterns),
        )

        return if (fileConfig.readOnly) readTools else readTools + writeTools
    }

    companion object : KLogging() {
        private val objectMapper = jacksonObjectMapper()

        // TODO: Auto-generate CONFIG_SCHEMA from FileAccessConfig (tracked separately)
        private val CONFIG_SCHEMA: JsonNode = objectMapper.readTree(
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
                        "description": "Maximum file size in megabytes that ReadFileTool will read. Clamped to [1, 50]. Values outside this range are clamped and a warning is logged.",
                        "default": 10,
                        "minimum": 1,
                        "maximum": 50
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
