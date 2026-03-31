package io.whozoss.agentos.plugins.file.tools

import com.fasterxml.jackson.module.kotlin.jacksonObjectMapper
import io.whozoss.agentos.plugins.file.resolveFilePath
import io.whozoss.agentos.sdk.tool.ContextAwareTool
import io.whozoss.agentos.sdk.tool.ToolExecutionContext
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.TimeoutCancellationException
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeout
import java.nio.file.AtomicMoveNotSupportedException
import java.nio.file.FileAlreadyExistsException
import java.nio.file.Files
import java.nio.file.NoSuchFileException
import java.nio.file.StandardCopyOption
import kotlin.time.Duration.Companion.seconds

/**
 * Move or rename a file within the same scope (project).
 *
 * Fails if source doesn't exist or destination already exists.
 * Cross-scope moves are not allowed.
 */
class MoveFileTool : ContextAwareTool<MoveFileTool.Input> {
    companion object {
        private val objectMapper = jacksonObjectMapper()
        private const val IO_TIMEOUT = 30L
    }

    override val name: String = "FILES__moveFile"

    override val description: String =
        """
        Move or rename a file within the same scope (project). Fails if the source does not exist
        or the destination already exists. File paths must start with "project://" prefix.
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
                    "description": "Source file path with prefix (e.g. \"project://old/path.ts\")"
                },
                "to": {
                    "type": "string",
                    "description": "Destination file path with prefix (e.g. \"project://new/path.ts\")"
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
                        moveFile(params.from, params.to, context)
                    }
                }
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
        context: ToolExecutionContext,
    ): String {
        val resolvedFrom = resolveFilePath(from, context.fileRoots, createIntent = false)
        val resolvedTo = resolveFilePath(to, context.fileRoots, createIntent = true)

        // Verify same scope
        if (resolvedFrom.scope != resolvedTo.scope) {
            throw IllegalArgumentException("Cannot move files between scopes")
        }

        // Check destination doesn't exist (explicit check needed because some filesystems
        // allow ATOMIC_MOVE to overwrite even without REPLACE_EXISTING)
        if (Files.exists(resolvedTo.absolutePath)) {
            return "Destination already exists: $to"
        }

        // Create parent directories if needed
        resolvedTo.absolutePath.parent?.let { parent ->
            Files.createDirectories(parent)
        }

        return try {
            // Use ATOMIC_MOVE for atomicity
            try {
                Files.move(resolvedFrom.absolutePath, resolvedTo.absolutePath, StandardCopyOption.ATOMIC_MOVE)
            } catch (e: AtomicMoveNotSupportedException) {
                // Fallback to regular move if atomic move not supported
                Files.move(resolvedFrom.absolutePath, resolvedTo.absolutePath)
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
