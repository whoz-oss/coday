package io.whozoss.agentos.plugins.file.tools

import io.whozoss.agentos.plugins.file.BoundaryPathResolver
import io.whozoss.agentos.plugins.file.SensitiveFilePatterns
import io.whozoss.agentos.sdk.tool.StandardTool
import io.whozoss.agentos.sdk.tool.ToolContext
import io.whozoss.agentos.sdk.tool.ToolExecutionResult
import kotlinx.coroutines.TimeoutCancellationException
import java.nio.file.Files
import java.nio.file.Path
import kotlin.io.path.isDirectory
import kotlin.io.path.name

/**
 * List files and directories in a folder (similar to ls command).
 *
 * Directories are suffixed with `/`. Inaccessible entries are marked with `(inaccessible)`.
 * Symlinks are validated and followed if they stay within the root boundary.
 */
class ListFilesTool(
    private val projectRoot: Path,
    private val configName: String? = null,
    private val denyPatterns: List<String> = SensitiveFilePatterns.DEFAULT_PATTERNS,
) : StandardTool<ListFilesTool.Input> {
    companion object {
        private const val IO_TIMEOUT = 30L
    }

    override val name: String = if (configName != null) "${configName}__listFiles" else "FILES__listFiles"

    override val description: String =
        """
        List directories and files in a folder (similar to ls command). Directories end with a slash.
        Use an empty string or "." to list the root directory.
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
                "relPath": {
                    "type": "string",
                    "description": "Relative path to list (e.g. \"src\", \"a/b\"). Use empty string or \".\" for root."
                }
            },
            "required": ["relPath"],
            "additionalProperties": false
        }
        """.trimIndent()

    data class Input(
        val relPath: String = "",
    )

    override suspend fun execute(
        input: Input?,
        context: ToolContext,
    ): ToolExecutionResult {
        val params = input ?: Input()

        return try {
            val entries = runIOWithTimeout(IO_TIMEOUT) { listDirectory(params.relPath) }
            ToolExecutionResult.success(entries.joinToString("\n"))
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
                "Error listing directory: ${e.message}",
                errorType = "LIST_ERROR",
                errorMessage = e.message,
            )
        }
    }

    private fun listDirectory(relPath: String): List<String> {
        // Treat empty string or "." as root
        val normalised = if (relPath.isBlank() || relPath == ".") "" else relPath
        val resolver = BoundaryPathResolver(projectRoot, denyPatterns)
        val targetPath =
            if (normalised.isEmpty()) {
                projectRoot.toRealPath()
            } else {
                resolver.resolve(normalised, createIntent = false)
            }

        require(targetPath.isDirectory()) { "Path is not a directory: $relPath" }

        return Files.list(targetPath).use { stream ->
            stream
                .map { path ->
                    try {
                        // Check if the path is accessible (this will fail for broken symlinks)
                        if (Files.isSymbolicLink(path) && !Files.exists(path)) {
                            "${path.name} (inaccessible)"
                        } else {
                            val name = path.name
                            if (Files.isDirectory(path)) "$name/" else name
                        }
                    } catch (e: Exception) {
                        "${path.name} (inaccessible)"
                    }
                }.toList()
        }
    }

}
