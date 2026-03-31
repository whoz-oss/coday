package io.whozoss.agentos.plugins.file.tools

import com.fasterxml.jackson.module.kotlin.jacksonObjectMapper
import io.whozoss.agentos.plugins.file.FilePrefixes
import io.whozoss.agentos.plugins.file.resolveFilePath
import io.whozoss.agentos.sdk.tool.ContextAwareTool
import io.whozoss.agentos.sdk.tool.ToolExecutionContext
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.TimeoutCancellationException
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeout
import java.nio.file.Files
import java.nio.file.Path
import java.util.concurrent.CompletableFuture
import java.util.concurrent.TimeUnit
import java.util.concurrent.TimeoutException
import kotlin.io.path.name
import kotlin.io.path.pathString
import kotlin.time.Duration.Companion.seconds

/**
 * Search for files by name and/or content.
 *
 * Uses ripgrep when available, falls back to Java NIO.
 * Implements smart return: returns content if total size ≤ 200KB, otherwise just paths.
 */
class SearchFilesTool : ContextAwareTool<SearchFilesTool.Input> {
    companion object {
        private val objectMapper = jacksonObjectMapper()
        private const val IO_TIMEOUT = 30L
        private const val CONTENT_THRESHOLD = 200 * 1024 // 200 KB
    }

    override val name: String = "FILES__searchFiles"

    override val description: String =
        """
        Search for files by name pattern and/or content text. At least one of fileName or fileContent must be provided.
        When both are provided, only files whose name matches fileName AND whose content matches fileContent are returned.
        Paths are prefixed with "project://".
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
                "fileName": {
                    "type": "string",
                    "description": "Partial file name or pattern to match against file names (e.g. \"config\", \"user.service\")"
                },
                "fileContent": {
                    "type": "string",
                    "description": "Text to search for inside file contents"
                },
                "path": {
                    "type": "string",
                    "description": "Optional path to restrict the search scope. Use \"project://\" prefix."
                },
                "fileTypes": {
                    "type": "array",
                    "items": { "type": "string" },
                    "description": "Optional array of file extensions to restrict content search (e.g. [\"ts\", \"json\"])"
                }
            },
            "additionalProperties": false
        }
        """.trimIndent()

    data class Input(
        val fileName: String? = null,
        val fileContent: String? = null,
        val path: String? = null,
        val fileTypes: List<String>? = null,
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

            if (params.fileName.isNullOrBlank() && params.fileContent.isNullOrBlank()) {
                return createErrorResponse("At least one of fileName or fileContent must be provided")
            }

            kotlinx.coroutines.runBlocking {
                withTimeout(IO_TIMEOUT.seconds) {
                    withContext(Dispatchers.IO) {
                        searchFiles(params, context)
                    }
                }
            }
        } catch (e: TimeoutCancellationException) {
            createErrorResponse("Search timed out after ${IO_TIMEOUT} seconds")
        } catch (e: IllegalArgumentException) {
            createErrorResponse(e.message ?: "Invalid search parameters")
        } catch (e: Exception) {
            createErrorResponse("Error searching files: ${e.message}")
        }
    }

    private fun searchFiles(
        params: Input,
        context: ToolExecutionContext,
    ): String {
        val searchRoot =
            if (params.path.isNullOrBlank()) {
                context.fileRoots["project"]!!
            } else {
                resolveFilePath(params.path, context.fileRoots, createIntent = false).absolutePath
            }

        // Try ripgrep first if fileContent is provided
        val results =
            if (!params.fileContent.isNullOrBlank()) {
                searchWithRipgrep(searchRoot, params) ?: searchWithNIO(searchRoot, params)
            } else {
                searchWithNIO(searchRoot, params)
            }

        return buildSearchResult(results, searchRoot, context.fileRoots["project"]!!)
    }

    private fun searchWithRipgrep(
        searchRoot: Path,
        params: Input,
    ): List<Path>? {
        var process: Process? = null
        return try {
            // Sanitize pattern
            val pattern = params.fileContent ?: return null
            if (pattern.contains('\u0000') || pattern.startsWith("-") || pattern.length > 1000) {
                return null // Fallback to NIO
            }

            val command = buildList {
                add("rg")
                add("--files-with-matches")
                add("--fixed-strings")
                add("--ignore-case")
                add("--color=never")
                params.fileName?.let {
                    add("--glob")
                    add("*$it*")
                }
                params.fileTypes?.forEach { ext ->
                    add("--glob")
                    add("*.$ext")
                }
                add("--")
                add(pattern)
                add(searchRoot.pathString)
            }

            process = ProcessBuilder(command).start()

            // Anti-deadlock pattern: read stdout asynchronously
            val stdoutFuture = CompletableFuture.supplyAsync {
                process!!.inputStream.bufferedReader().use { it.readLines() }
            }

            val completed = process.waitFor(IO_TIMEOUT, TimeUnit.SECONDS)
            if (!completed) {
                process.destroyForcibly()
                throw TimeoutException("Ripgrep timeout after ${IO_TIMEOUT}s")
            }

            val lines = stdoutFuture.get()

            if (process.exitValue() == 0 || process.exitValue() == 1) {
                lines.map { Path.of(it) }
            } else {
                null // Error, fallback to NIO
            }
        } catch (e: Exception) {
            null // Ripgrep not available or failed, fallback to NIO
        } finally {
            process?.destroyForcibly()
        }
    }

    private fun searchWithNIO(
        searchRoot: Path,
        params: Input,
    ): List<Path> {
        val results = mutableListOf<Path>()

        Files.walk(searchRoot, 20).use { stream ->
            stream.forEach { path ->
                if (!Files.isRegularFile(path)) return@forEach

                // Filter by fileName
                if (params.fileName != null && !path.name.contains(params.fileName, ignoreCase = true)) {
                    return@forEach
                }

                // Filter by fileType
                if (params.fileTypes != null) {
                    val ext = path.name.substringAfterLast('.', "")
                    if (!params.fileTypes.contains(ext)) return@forEach
                }

                // Filter by fileContent
                if (params.fileContent != null) {
                    try {
                        val content = Files.readString(path)
                        if (!content.contains(params.fileContent, ignoreCase = true)) {
                            return@forEach
                        }
                    } catch (e: Exception) {
                        return@forEach // Skip unreadable files
                    }
                }

                results.add(path)
            }
        }

        return results
    }

    private fun buildSearchResult(
        files: List<Path>,
        searchRoot: Path,
        projectRoot: Path,
    ): String {
        if (files.isEmpty()) {
            return "No matching files found."
        }

        // Calculate total size
        var totalSize = 0L
        val contents = mutableListOf<Pair<String, String?>>()

        for (file in files) {
            val relPath = projectRoot.relativize(file).pathString
            try {
                val content = Files.readString(file)
                totalSize += content.length
                contents.add(relPath to content)

                if (totalSize > CONTENT_THRESHOLD) break
            } catch (e: Exception) {
                contents.add(relPath to null) // Binary or unreadable
            }
        }

        // If total size exceeds threshold, return paths only
        if (totalSize > CONTENT_THRESHOLD || contents.size < files.size) {
            return files.map { file ->
                val relPath = projectRoot.relativize(file).pathString
                "${FilePrefixes.PROJECT}$relPath"
            }.joinToString("\n")
        }

        // Return content with headers
        return contents.joinToString("\n\n") { (relPath, content) ->
            val header = "=== ${FilePrefixes.PROJECT}$relPath ==="
            val body = content ?: "[binary or unreadable]"
            "$header\n$body"
        }
    }

    private fun createErrorResponse(message: String): String = message
}
