package io.whozoss.agentos.plugins.file

import com.fasterxml.jackson.databind.JsonNode
import com.fasterxml.jackson.module.kotlin.jacksonObjectMapper
import io.whozoss.agentos.plugins.file.image.ImageProcessor
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

        val imageMaxDimension = config.get("imageMaxDimension")?.asInt() ?: ImageProcessor.MAX_DIMENSION
        val imageJpegQuality = config.get("imageJpegQuality")?.asDouble()?.toFloat() ?: ImageProcessor.JPEG_QUALITY
        val imageMaxSourcePixels = config.get("imageMaxSourcePixels")?.asLong() ?: ImageProcessor.MAX_SOURCE_PIXELS
        val imagePassThroughMaxBytes = config.get("imagePassThroughMaxBytes")?.asLong() ?: ImageProcessor.PASS_THROUGH_MAX_BYTES

        val spreadsheetMaxRows = config.get("spreadsheetMaxRows")?.asInt() ?: ReadSpreadsheetTool.MAX_ROWS_PER_CALL
        val spreadsheetMaxOutputChars = config.get("spreadsheetMaxOutputChars")?.asInt() ?: ReadSpreadsheetTool.MAX_OUTPUT_CHARS
        val spreadsheetMaxColumns = config.get("spreadsheetMaxColumns")?.asInt() ?: ReadSpreadsheetTool.MAX_COLUMNS
        val spreadsheetMaxCellChars = config.get("spreadsheetMaxCellChars")?.asInt() ?: ReadSpreadsheetTool.MAX_CELL_CHARS

        val readMaxSizeBytes = readMaxSizeMb * 1024 * 1024
        val denyPatterns = SensitiveFilePatterns.DEFAULT_PATTERNS + extraDenyPatterns

        val readTools = listOf(
            ListFilesTool(rootPath, configName, denyPatterns),
            ReadFileTool(rootPath, configName, readMaxSizeBytes, denyPatterns),
            ReadAsImageTool(
                rootPath,
                configName,
                readMaxSizeBytes,
                denyPatterns,
                imageMaxDimension = imageMaxDimension,
                imageJpegQuality = imageJpegQuality,
                imageMaxSourcePixels = imageMaxSourcePixels,
                imagePassThroughMaxBytes = imagePassThroughMaxBytes,
            ),
            ReadSpreadsheetTool(
                rootPath,
                configName,
                readMaxSizeBytes,
                denyPatterns,
                spreadsheetMaxRows = spreadsheetMaxRows,
                spreadsheetMaxOutputChars = spreadsheetMaxOutputChars,
                spreadsheetMaxColumns = spreadsheetMaxColumns,
                spreadsheetMaxCellChars = spreadsheetMaxCellChars,
            ),
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
                        "description": "Maximum file size in megabytes that readFile, readAsImage and readSpreadsheet will read. Default is 10 MB.",
                        "default": 10
                    },
                    "imageMaxDimension": {
                        "type": "integer",
                        "title": "Image Max Dimension (px)",
                        "description": "Longest-edge size, in pixels, that readAsImage sends to the LLM; larger images are downscaled. Default is 1024.",
                        "default": 1024
                    },
                    "imageJpegQuality": {
                        "type": "number",
                        "title": "Image JPEG Quality",
                        "description": "JPEG re-encoding quality for readAsImage, between 0 and 1. Default is 0.80.",
                        "default": 0.80
                    },
                    "imageMaxSourcePixels": {
                        "type": "integer",
                        "title": "Image Max Source Pixels",
                        "description": "Decode-bomb guard: readAsImage refuses to decode any source or embedded image above this pixel count. Default is 50000000.",
                        "default": 50000000
                    },
                    "imagePassThroughMaxBytes": {
                        "type": "integer",
                        "title": "Image Pass-Through Max Size (bytes)",
                        "description": "Originals at or below this byte size that already fit the max dimension are sent untouched instead of re-encoded. Default is 1048576 (1 MB).",
                        "default": 1048576
                    },
                    "spreadsheetMaxRows": {
                        "type": "integer",
                        "title": "Spreadsheet Max Rows",
                        "description": "Maximum rows readSpreadsheet emits per call across the selected sheets; a larger sheet is paged via startRow. Default is 1000.",
                        "default": 1000
                    },
                    "spreadsheetMaxOutputChars": {
                        "type": "integer",
                        "title": "Spreadsheet Max Output Chars",
                        "description": "CSV character budget per readSpreadsheet call. Default is 100000.",
                        "default": 100000
                    },
                    "spreadsheetMaxColumns": {
                        "type": "integer",
                        "title": "Spreadsheet Max Columns",
                        "description": "Columns beyond this are not emitted by readSpreadsheet (flagged in the sheet header). Default is 256.",
                        "default": 256
                    },
                    "spreadsheetMaxCellChars": {
                        "type": "integer",
                        "title": "Spreadsheet Max Cell Chars",
                        "description": "A single cell longer than this is cut by readSpreadsheet. Default is 5000.",
                        "default": 5000
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
