package io.whozoss.agentos.plugins.file.tools

import com.fasterxml.jackson.module.kotlin.jacksonObjectMapper
import io.whozoss.agentos.plugins.file.BoundaryPathResolver
import io.whozoss.agentos.plugins.file.SensitiveFilePatterns
import io.whozoss.agentos.sdk.tool.StandardTool
import io.whozoss.agentos.sdk.tool.ToolContext
import kotlinx.coroutines.TimeoutCancellationException
import java.nio.file.AtomicMoveNotSupportedException
import java.nio.file.FileAlreadyExistsException
import java.nio.file.Files
import java.nio.file.NoSuchFileException
import java.nio.file.Path
import java.nio.file.StandardCopyOption

/**
 * Move or rename a file within the configured root directory.
 *
 * Fails if source doesn't exist or destination already exists.
 */
class MoveFileTool(
    private val projectRoot: Path,
    private val configName: String? = null,
    private val denyPatterns: List<String> = SensitiveFilePatterns.DEFAULT_PATTERNS,
) : StandardTool<MoveFileTool.Input> {
    companion object {
        private val objectMapper = jacksonObjectMapper()
        private const val IO_TIMEOUT = 30L
    }

    override val name: String = if (configName != null) "${configName}__moveFile" else "FILES__moveFile"

    override val description: String =
        """
        Move or rename a file. Fails if the source does not exist or the destination already exists.
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
                "from": {
                    "type": "string",
                    "description": "Source relative file path (e.g. \"old/path.ts\")"
                },
                "to": {
                    "type": "string",
                    "description": "Destination relative file path (e.g. \"new/path.ts\")"
                }
            },
            "required": ["from", "to"],
            "additionalProperties": false
        }
        """.trimIndent()

    data class Input(
        val from: String = "",
        val to: String = "",
    )

    override suspend fun execute(input: Input?, context: ToolContext): String {
        val params = input ?: Input()

        return try {
            runIOWithTimeout(IO_TIMEOUT) {
                moveFile(params.from, params.to)
            }
        } catch (e: TimeoutCancellationException) {
            createErrorResponse("Operation timed out after ${IO_TIMEOUT} seconds")
        } catch (e: IllegalArgumentException) {
            createErrorResponse(e.message ?: "Invalid path")
        } catch (e: Exception) {
            createErrorResponse("Error moving file: ${e.message}")
        }
    }

    private fun moveFile(
        from: String,
        to: String,
    ): String {
        val resolver = BoundaryPathResolver(projectRoot, denyPatterns)
        val resolvedFrom = resolver.resolve(from, createIntent = false)
        val resolvedTo = resolver.resolve(to, createIntent = true)

        // Check destination doesn't exist
        if (Files.exists(resolvedTo)) {
            return "Destination already exists: $to"
        }

        // Create parent directories if needed
        resolvedTo.parent?.let { parent ->
            Files.createDirectories(parent)
        }

        return try {
            try {
                Files.move(resolvedFrom, resolvedTo, StandardCopyOption.ATOMIC_MOVE)
            } catch (e: AtomicMoveNotSupportedException) {
                Files.move(resolvedFrom, resolvedTo)
            }
            "File moved successfully"
        } catch (e: FileAlreadyExistsException) {
            "Destination already exists: $to"
        } catch (e: NoSuchFileException) {
            "Source file not found: $from"
        }
    }

    private fun createErrorResponse(message: String): String = message
}
