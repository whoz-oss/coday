package io.whozoss.agentos.plugins.file.tools

import com.fasterxml.jackson.module.kotlin.jacksonObjectMapper
import io.whozoss.agentos.plugins.file.resolveFilePath
import io.whozoss.agentos.sdk.tool.ContextAwareTool
import io.whozoss.agentos.sdk.tool.ToolExecutionContext
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.TimeoutCancellationException
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeout
import java.nio.file.Files
import java.nio.file.NoSuchFileException
import kotlin.io.path.isDirectory
import kotlin.time.Duration.Companion.seconds

/**
 * Remove a file.
 *
 * Only removes files, not directories. Fails if file doesn't exist or is a directory.
 */
class RemoveFileTool : ContextAwareTool<RemoveFileTool.Input> {
    companion object {
        private val objectMapper = jacksonObjectMapper()
        private const val IO_TIMEOUT = 30L
    }

    override val name: String = "FILES__remove"

    override val description: String =
        """
        Remove a file. File path must start with "project://" prefix.
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
                    "description": "File path with prefix (e.g., \"project://temp/old.txt\")"
                }
            },
            "required": ["path"],
            "additionalProperties": false
        }
        """.trimIndent()

    data class Input(
        val path: String = "",
    )

    override fun executeWithContext(
        input: Input?,
        context: ToolExecutionContext,
    ): String {
        val params = input ?: Input()

        return try {
            if (!context.fileRoots.containsKey("project")) {
                return createErrorResponse("File tools require a configured namespace with project root")
            }

            // Check read-only mode
            if (context.properties["readOnly"] == "true") {
                return createErrorResponse("Cannot modify files in read-only mode")
            }

            kotlinx.coroutines.runBlocking {
                withTimeout(IO_TIMEOUT.seconds) {
                    withContext(Dispatchers.IO) {
                        removeFile(params.path, context)
                    }
                }
            }
        } catch (e: TimeoutCancellationException) {
            createErrorResponse("Operation timed out after ${IO_TIMEOUT} seconds")
        } catch (e: IllegalArgumentException) {
            createErrorResponse(e.message ?: "Invalid path")
        } catch (e: Exception) {
            createErrorResponse("Error removing file: ${e.message}")
        }
    }

    private fun removeFile(
        path: String,
        context: ToolExecutionContext,
    ): String {
        val resolved = resolveFilePath(path, context.fileRoots, createIntent = false)

        // Don't allow removing directories
        if (resolved.absolutePath.isDirectory()) {
            throw IllegalArgumentException("Cannot remove directories: $path")
        }

        return try {
            Files.delete(resolved.absolutePath)
            "File deleted successfully"
        } catch (e: NoSuchFileException) {
            "File not found: $path"
        }
    }

    private fun createErrorResponse(message: String): String = message
}
