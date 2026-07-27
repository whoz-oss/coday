package io.whozoss.agentos.plugins.file.tools

import io.whozoss.agentos.plugins.file.BoundaryPathResolver
import io.whozoss.agentos.plugins.file.SensitiveFilePatterns
import io.whozoss.agentos.sdk.tool.StandardTool
import io.whozoss.agentos.sdk.tool.ToolContext
import io.whozoss.agentos.sdk.tool.ToolExecutionResult
import kotlinx.coroutines.TimeoutCancellationException
import java.nio.charset.MalformedInputException
import java.nio.charset.StandardCharsets
import java.nio.file.Files
import java.nio.file.Path
import kotlin.io.path.fileSize

/**
 * Read content from a text file.
 *
 * Text files only (UTF-8). Binary files return "[binary or unreadable file]".
 * For images and PDFs use [ReadAsImageTool]; for Word (.docx) use [ReadDocumentTool];
 * for Excel spreadsheets use [ReadSpreadsheetTool].
 */
class ReadFileTool(
    private val projectRoot: Path,
    private val configName: String? = null,
    private val readMaxSizeBytes: Long = DEFAULT_READ_MAX_SIZE,
    private val denyPatterns: List<String> = SensitiveFilePatterns.DEFAULT_PATTERNS,
) : StandardTool<ReadFileTool.Input> {
    companion object {
        private const val IO_TIMEOUT = 30L
        private const val DEFAULT_READ_MAX_SIZE = 10L * 1024 * 1024 // 10 MB
    }

    override val name: String = if (configName != null) "${configName}__readFile" else "FILES__readFile"

    override val description: String =
        """
        Read content from a text file. Use searchFiles to find files.
        Text files only (UTF-8). For images and PDFs use readAsImage; for Word (.docx) use
        readDocument; for Excel spreadsheets (.xlsx) use readSpreadsheet.
        """.trimIndent()

    override val version: String = "1.0.0"

    override val paramType: Class<Input> = Input::class.java

    // language=JSON
    override val inputSchema: String =
        """
        {
            "${'$'}schema": "https://json-schema.org/draft/2020-12/schema",
            "type": "object",
            "properties": {
                "filePath": {
                    "type": "string",
                    "description": "Relative file path (e.g. \"src/main.ts\", \"README.md\")"
                }
            },
            "required": ["filePath"],
            "additionalProperties": false
        }
        """.trimIndent()

    data class Input(
        val filePath: String = "",
    )

    override suspend fun execute(
        input: Input?,
        context: ToolContext,
    ): ToolExecutionResult {
        val params = input ?: Input()

        return try {
            val content = runIOWithTimeout(IO_TIMEOUT) { readFile(params.filePath) }
            ToolExecutionResult.success(content)
        } catch (e: TimeoutCancellationException) {
            ToolExecutionResult.error(
                "Operation timed out after ${IO_TIMEOUT} seconds",
                errorType = "TIMEOUT",
                errorMessage = e.message,
            )
        } catch (e: IllegalArgumentException) {
            ToolExecutionResult.error(
                e.message ?: "Invalid path",
                errorType = "INVALID_INPUT",
                errorMessage = e.message,
            )
        } catch (e: Exception) {
            ToolExecutionResult.error(
                "Error reading file: ${e.message}",
                errorType = "READ_ERROR",
                errorMessage = e.message,
            )
        }
    }

    private fun readFile(filePath: String): String {
        val resolver = BoundaryPathResolver(projectRoot, denyPatterns)
        val resolved = resolver.resolve(filePath, createIntent = false)

        // Check file size
        val size = resolved.fileSize()
        if (size > readMaxSizeBytes) {
            throw IllegalArgumentException(
                "File exceeds maximum size of ${readMaxSizeBytes / 1024 / 1024} MB: $filePath",
            )
        }

        // Try to read as UTF-8 text
        return try {
            Files.readString(resolved, StandardCharsets.UTF_8)
        } catch (e: MalformedInputException) {
            "[binary or unreadable file]"
        }
    }

}
