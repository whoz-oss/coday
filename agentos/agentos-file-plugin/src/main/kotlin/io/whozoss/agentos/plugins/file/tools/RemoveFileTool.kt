package io.whozoss.agentos.plugins.file.tools

import com.fasterxml.jackson.module.kotlin.jacksonObjectMapper
import io.whozoss.agentos.plugins.file.BoundaryPathResolver
import io.whozoss.agentos.plugins.file.SensitiveFilePatterns
import io.whozoss.agentos.sdk.tool.StandardTool
import kotlinx.coroutines.TimeoutCancellationException
import java.nio.file.Files
import java.nio.file.NoSuchFileException
import java.nio.file.Path
import kotlin.io.path.isDirectory

/**
 * Remove a file.
 *
 * Only removes files, not directories. Fails if file doesn't exist or is a directory.
 */
class RemoveFileTool(
    private val projectRoot: Path,
    private val configName: String? = null,
    private val denyPatterns: List<String> = SensitiveFilePatterns.DEFAULT_PATTERNS,
) : StandardTool<RemoveFileTool.Input> {
    companion object {
        private val objectMapper = jacksonObjectMapper()
        private const val IO_TIMEOUT = 30L
    }

    override val name: String = if (configName != null) "${configName}__remove" else "FILES__remove"

    override val description: String =
        """
        Remove a file. Only removes files, not directories.
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
                "path": {
                    "type": "string",
                    "description": "Relative file path (e.g. \"temp/old.txt\")"
                }
            },
            "required": ["path"],
            "additionalProperties": false
        }
        """.trimIndent()

    data class Input(
        val path: String = "",
    )

    override fun execute(input: Input?): String {
        val params = input ?: Input()

        return try {
            runIOWithTimeout(IO_TIMEOUT) {
                removeFile(params.path)
            }
        } catch (e: TimeoutCancellationException) {
            createErrorResponse("Operation timed out after ${IO_TIMEOUT} seconds")
        } catch (e: IllegalArgumentException) {
            createErrorResponse(e.message ?: "Invalid path")
        } catch (e: Exception) {
            createErrorResponse("Error removing file: ${e.message}")
        }
    }

    private fun removeFile(path: String): String {
        val resolver = BoundaryPathResolver(projectRoot, denyPatterns)
        val resolved = resolver.resolve(path, createIntent = false)

        // Don't allow removing directories
        if (resolved.isDirectory()) {
            throw IllegalArgumentException("Cannot remove directories: $path")
        }

        return try {
            Files.delete(resolved)
            "File deleted successfully"
        } catch (e: NoSuchFileException) {
            "File not found: $path"
        }
    }

    private fun createErrorResponse(message: String): String = message
}
