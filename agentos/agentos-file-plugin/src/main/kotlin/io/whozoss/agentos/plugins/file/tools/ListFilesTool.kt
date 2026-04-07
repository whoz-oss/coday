package io.whozoss.agentos.plugins.file.tools

import com.fasterxml.jackson.module.kotlin.jacksonObjectMapper
import io.whozoss.agentos.plugins.file.resolveFilePath
import io.whozoss.agentos.sdk.tool.StandardTool
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.TimeoutCancellationException
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeout
import java.nio.file.Files
import java.nio.file.Path
import kotlin.io.path.isDirectory
import kotlin.io.path.name
import kotlin.time.Duration.Companion.seconds

/**
 * List files and directories in a folder (similar to ls command).
 *
 * Directories are suffixed with `/`. Inaccessible entries are marked with `(inaccessible)`.
 * Symlinks are validated and followed if they stay within the root boundary.
 */
class ListFilesTool(
    private val projectRoot: Path,
    private val configName: String? = null,
) : StandardTool<ListFilesTool.Input> {
    companion object {
        private val objectMapper = jacksonObjectMapper()
        private const val IO_TIMEOUT = 30L
    }

    override val name: String = if (configName != null) "${configName}__FILES__listFiles" else "FILES__listFiles"

    override val description: String =
        """
        List directories and files in a folder (similar to ls command). Directories end with a slash.
        Path must start with "project://" prefix.
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
                    "description": "Path with prefix (e.g., \"project://src\" or \"exchange://\")"
                }
            },
            "required": ["relPath"],
            "additionalProperties": false
        }
        """.trimIndent()

    data class Input(
        val relPath: String = "",
    )

    override fun execute(input: Input?): String {
        val params = input ?: Input()

        return try {
            // Require explicit prefix
            if (params.relPath.isEmpty() || params.relPath == "." || params.relPath == "/") {
                return createErrorResponse(
                    "Path must start with \"project://\" or \"exchange://\" prefix. " +
                        "Use \"project://\" to list project files or \"exchange://\" to list files shared with users.",
                )
            }

            // Execute with timeout
            val entries =
                kotlinx.coroutines.runBlocking {
                    withTimeout(IO_TIMEOUT.seconds) {
                        withContext(Dispatchers.IO) {
                            listDirectory(params.relPath)
                        }
                    }
                }

            entries.joinToString("\n")
        } catch (e: TimeoutCancellationException) {
            createErrorResponse("Operation timed out after ${IO_TIMEOUT} seconds")
        } catch (e: IllegalArgumentException) {
            createErrorResponse(e.message ?: "Invalid path")
        } catch (e: Exception) {
            createErrorResponse("Error listing directory: ${e.message}")
        }
    }

    private fun listDirectory(relPath: String): List<String> {
        val resolved = resolveFilePath(relPath, mapOf("project" to projectRoot), createIntent = false)

        if (!resolved.absolutePath.isDirectory()) {
            throw IllegalArgumentException("Path is not a directory: $relPath")
        }

        return Files.list(resolved.absolutePath).use { stream ->
            stream.map { path ->
                try {
                    // Check if the path is accessible (this will fail for broken symlinks)
                    if (Files.isSymbolicLink(path) && !Files.exists(path)) {
                        return@map "${path.name} (inaccessible)"
                    }
                    val isDir = Files.isDirectory(path)
                    val name = path.name
                    if (isDir) "$name/" else name
                } catch (e: Exception) {
                    "${path.name} (inaccessible)"
                }
            }.toList()
        }
    }

    private fun createErrorResponse(message: String): String = message
}
