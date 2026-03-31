package io.whozoss.agentos.plugins.file.tools

import com.fasterxml.jackson.module.kotlin.jacksonObjectMapper
import io.whozoss.agentos.plugins.file.resolveFilePath
import io.whozoss.agentos.sdk.tool.ContextAwareTool
import io.whozoss.agentos.sdk.tool.ToolExecutionContext
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.TimeoutCancellationException
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeout
import java.nio.charset.MalformedInputException
import java.nio.charset.StandardCharsets
import java.nio.file.Files
import kotlin.io.path.fileSize
import kotlin.time.Duration.Companion.seconds

/**
 * Read content from a text file.
 *
 * V1: Text files only (UTF-8). Binary files return "[binary or unreadable file]".
 * V2 planned: PDF and image support via MessageContent polymorphism.
 */
class ReadFileTool : ContextAwareTool<ReadFileTool.Input> {
    companion object {
        private val objectMapper = jacksonObjectMapper()
        private const val IO_TIMEOUT = 30L
        private const val READ_MAX_SIZE = 10 * 1024 * 1024 // 10 MB
    }

    override val name: String = "FILES__readFile"

    override val description: String =
        """
        Read content from a text file. File path must start with "project://" prefix.
        Use searchFiles to find files. V1: text files only (UTF-8). PDF and image support planned for V2.
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
                    "description": "File path with prefix (e.g., \"project://src/main.ts\")"
                }
            },
            "required": ["filePath"],
            "additionalProperties": false
        }
        """.trimIndent()

    data class Input(
        val filePath: String = "",
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

            kotlinx.coroutines.runBlocking {
                withTimeout(IO_TIMEOUT.seconds) {
                    withContext(Dispatchers.IO) {
                        readFile(params.filePath, context)
                    }
                }
            }
        } catch (e: TimeoutCancellationException) {
            createErrorResponse("Operation timed out after ${IO_TIMEOUT} seconds")
        } catch (e: IllegalArgumentException) {
            createErrorResponse(e.message ?: "Invalid path")
        } catch (e: Exception) {
            createErrorResponse("Error reading file: ${e.message}")
        }
    }

    private fun readFile(
        filePath: String,
        context: ToolExecutionContext,
    ): String {
        val resolved = resolveFilePath(filePath, context.fileRoots, createIntent = false)

        // Check file size
        val size = resolved.absolutePath.fileSize()
        if (size > READ_MAX_SIZE) {
            throw IllegalArgumentException(
                "File exceeds maximum size of ${READ_MAX_SIZE / 1024 / 1024} MB: $filePath",
            )
        }

        // Try to read as UTF-8 text
        return try {
            Files.readString(resolved.absolutePath, StandardCharsets.UTF_8)
        } catch (e: MalformedInputException) {
            "[binary or unreadable file]"
        }
    }

    private fun createErrorResponse(message: String): String = message
}
