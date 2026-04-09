package io.whozoss.agentos.plugins.file.tools

import com.fasterxml.jackson.module.kotlin.jacksonObjectMapper
import io.whozoss.agentos.plugins.file.BoundaryPathResolver
import io.whozoss.agentos.sdk.tool.StandardTool
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.TimeoutCancellationException
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeout
import java.nio.charset.MalformedInputException
import java.nio.charset.StandardCharsets
import java.nio.file.Files
import java.nio.file.Path
import kotlin.io.path.fileSize
import kotlin.time.Duration.Companion.seconds

/**
 * Read content from a text file.
 *
 * V1: Text files only (UTF-8). Binary files return "[binary or unreadable file]".
 * V2 planned: PDF and image support via MessageContent polymorphism.
 */
class ReadFileTool(
    private val projectRoot: Path,
    private val configName: String? = null,
) : StandardTool<ReadFileTool.Input> {
    companion object {
        private val objectMapper = jacksonObjectMapper()
        private const val IO_TIMEOUT = 30L
        private const val READ_MAX_SIZE = 10 * 1024 * 1024 // 10 MB
    }

    override val name: String = if (configName != null) "${configName}__readFile" else "FILES__readFile"

    override val description: String =
        """
        Read content from a text file. Use searchFiles to find files.
        V1: text files only (UTF-8). PDF and image support planned for V2.
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

    override fun execute(input: Input?): String {
        val params = input ?: Input()

        return try {
            kotlinx.coroutines.runBlocking {
                withTimeout(IO_TIMEOUT.seconds) {
                    withContext(Dispatchers.IO) {
                        readFile(params.filePath)
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

    private fun readFile(filePath: String): String {
        val resolver = BoundaryPathResolver(projectRoot)
        val resolved = resolver.resolve(filePath, createIntent = false)

        // Check file size
        val size = resolved.fileSize()
        if (size > READ_MAX_SIZE) {
            throw IllegalArgumentException(
                "File exceeds maximum size of ${READ_MAX_SIZE / 1024 / 1024} MB: $filePath",
            )
        }

        // Try to read as UTF-8 text
        return try {
            Files.readString(resolved, StandardCharsets.UTF_8)
        } catch (e: MalformedInputException) {
            "[binary or unreadable file]"
        }
    }

    private fun createErrorResponse(message: String): String = message
}
