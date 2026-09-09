package io.whozoss.agentos.plugins.file.tools

import com.fasterxml.jackson.annotation.JsonSubTypes
import com.fasterxml.jackson.annotation.JsonTypeInfo
import com.fasterxml.jackson.module.kotlin.jacksonObjectMapper
import io.whozoss.agentos.plugins.file.BoundaryPathResolver
import io.whozoss.agentos.plugins.file.SensitiveFilePatterns
import io.whozoss.agentos.sdk.tool.EnrichmentResult
import io.whozoss.agentos.sdk.tool.IntermediatePhaseDescriptor
import io.whozoss.agentos.sdk.tool.StandardTool
import io.whozoss.agentos.sdk.tool.ToolContext
import io.whozoss.agentos.sdk.tool.ToolExecutionResult
import kotlinx.coroutines.TimeoutCancellationException
import mu.KLogging
import java.nio.charset.StandardCharsets
import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.StandardCopyOption
import java.util.UUID
import kotlin.io.path.exists
import kotlin.io.path.fileSize

/**
 * Edit one or more files in a single tool call.
 *
 * Supports two operations:
 * - "write": Replace entire file content (or create it)
 * - "patch": Replace specific chunks within an existing file
 *
 * Edits are executed independently: failure on one file doesn't prevent others.
 * All writes use atomic move pattern with cleanup.
 */
class EditFilesTool(
    private val projectRoot: Path,
    private val configName: String? = null,
    private val denyPatterns: List<String> = SensitiveFilePatterns.DEFAULT_PATTERNS,
) : StandardTool<EditFilesTool.Input> {
    companion object : KLogging() {
        private const val IO_TIMEOUT = 30L
        private const val WRITE_SIZE_THRESHOLD = 64 * 1024 // 64 KB
        private const val PATCH_MIN_CHUNK = 15
        private val PID = ProcessHandle.current().pid()
        private val objectMapper = jacksonObjectMapper()
    }

    override val name: String = if (configName != null) "${configName}__editFiles" else "FILES__editFiles"

    override val description: String =
        """
        Edit one or more files in a single tool call. Each edit targets a specific file and specifies an operation:
        "write" replaces the entire file content (or creates it), "patch" replaces specific chunks within an existing file.
        Edits are executed independently: a failure on one file does not prevent others from being processed.
        """.trimIndent()

    override val version: String = "1.0.0"

    override val paramType: Class<Input> = Input::class.java

    // language=JSON
    override val inputSchema: String =
        $$"""
        {
            "$schema": "https://json-schema.org/draft/2020-12/schema",
            "type": "object",
            "properties": {
                "edits": {
                    "type": "array",
                    "description": "List of file edits to perform.",
                    "items": {
                        "type": "object",
                        "properties": {
                            "operation": {
                                "type": "string",
                                "enum": ["write", "patch"],
                                "description": "\"write\" to overwrite/create the file, \"patch\" to replace specific chunks."
                            },
                            "path": {
                                "type": "string",
                                "description": "Relative file path (e.g. \"src/main.ts\")"
                            },
                            "content": {
                                "type": "string",
                                "description": "Full file content. Required for \"write\" operation."
                            },
                            "replacements": {
                                "type": "array",
                                "description": "Required for \"patch\" operation.",
                                "items": {
                                    "type": "object",
                                    "properties": {
                                        "oldPart": {
                                            "type": "string",
                                            "description": "Existing content to replace (must be unique and at least 15 chars)."
                                        },
                                        "newPart": {
                                            "type": "string",
                                            "description": "Replacement content."
                                        }
                                    }
                                }
                            }
                        }
                    }
                }
            },
            "required": ["edits"],
            "additionalProperties": false
        }
        """.trimIndent()

    @JsonTypeInfo(use = JsonTypeInfo.Id.NAME, property = "operation")
    @JsonSubTypes(
        JsonSubTypes.Type(value = WriteEdit::class, name = "write"),
        JsonSubTypes.Type(value = PatchEdit::class, name = "patch"),
    )
    sealed interface Edit {
        val path: String
    }

    data class WriteEdit(
        override val path: String,
        val content: String = "",
    ) : Edit

    data class PatchEdit(
        override val path: String,
        val replacements: List<Replacement> = emptyList(),
    ) : Edit

    data class Replacement(
        val oldPart: String,
        val newPart: String,
    )

    data class Input(
        val edits: List<Edit> = emptyList(),
    )

    override suspend fun getIntermediatePhaseCount(): Int = 1

    override suspend fun getIntermediatePhaseDescriptor(
        phaseIndex: Int,
        previousContent: String?,
    ): IntermediatePhaseDescriptor =
        IntermediatePhaseDescriptor(
            inputSchema =
                """
                {
                    "type": "object",
                    "properties": {
                        "filePaths": {
                            "type": "array",
                            "items": { "type": "string" },
                            "description": "Relative paths of the files that need to be edited"
                        }
                    },
                    "required": ["filePaths"],
                    "additionalProperties": false
                }
                """.trimIndent(),
            prompt = "Identify the relative file paths that need to be edited to fulfill the user's intention.",
        )

    override suspend fun enrich(
        phaseIndex: Int,
        phaseParametersJson: String,
        context: ToolContext,
    ): EnrichmentResult {
        return try {
            val input = objectMapper.readTree(phaseParametersJson)
            val paths = input.get("filePaths")?.map { it.asText() } ?: emptyList()
            if (paths.isEmpty()) {
                return EnrichmentResult(success = false, errorMessage = "No file paths provided")
            }
            val resolver = BoundaryPathResolver(projectRoot, denyPatterns)
            val contents =
                paths.map { path ->
                    try {
                        val resolved = resolver.resolve(path, createIntent = false)
                        val content = Files.readString(resolved, StandardCharsets.UTF_8)
                        "=== $path ===\n$content"
                    } catch (e: Exception) {
                        "=== $path === [Error: ${e.message}]"
                    }
                }
            EnrichmentResult(
                success = true,
                content = "Current file contents:\n${contents.joinToString("\n\n")}",
            )
        } catch (e: Exception) {
            EnrichmentResult(success = false, errorMessage = "Failed to read files: ${e.message}")
        }
    }

    override suspend fun execute(
        input: Input?,
        context: ToolContext,
    ): ToolExecutionResult {
        val params = input ?: Input()

        return try {
            if (params.edits.isEmpty()) {
                return ToolExecutionResult.error("No edits provided.", errorType = "INVALID_INPUT")
            }

            val result = runIOWithTimeout(IO_TIMEOUT) { processEdits(params.edits) }
            ToolExecutionResult.success(result)
        } catch (e: TimeoutCancellationException) {
            ToolExecutionResult.error(
                "Operation timed out after ${IO_TIMEOUT} seconds",
                errorType = "TIMEOUT",
                errorMessage = e.message,
            )
        } catch (e: Exception) {
            ToolExecutionResult.error(
                "Error processing edits: ${e.message}",
                errorType = "EDIT_ERROR",
                errorMessage = e.message,
            )
        }
    }

    private fun processEdits(edits: List<Edit>): String {
        val results =
            edits.map { edit ->
                try {
                    when (edit) {
                        is WriteEdit -> processWrite(edit.path, edit.content)
                        is PatchEdit -> processPatch(edit.path, edit.replacements)
                    }
                } catch (e: Exception) {
                    "${edit.path}: Error — ${e.message}"
                }
            }
        return results.joinToString("\n")
    }

    private fun processWrite(
        path: String,
        content: String,
    ): String {
        val resolver = BoundaryPathResolver(projectRoot, denyPatterns)
        val resolved = resolver.resolve(path, createIntent = true)

        // Check threshold on EXISTING files
        if (resolved.exists()) {
            val existingSize = resolved.fileSize()
            if (existingSize > WRITE_SIZE_THRESHOLD) {
                return "$path: File full write not accepted: file exceeds the size threshold of ${WRITE_SIZE_THRESHOLD / 1024}kB. Use patch operation instead."
            }
        }

        // Atomic write with cleanup
        val tmpPath = resolved.resolveSibling("${resolved.fileName}.$PID.${UUID.randomUUID()}.tmp")
        return try {
            // Create parent directories
            resolved.parent?.let { Files.createDirectories(it) }

            Files.writeString(tmpPath, content, StandardCharsets.UTF_8)
            Files.move(tmpPath, resolved, StandardCopyOption.ATOMIC_MOVE, StandardCopyOption.REPLACE_EXISTING)
            "$path: File write success"
        } finally {
            try {
                Files.deleteIfExists(tmpPath)
            } catch (e: Exception) {
                logger.warn(e) { "Failed to clean up temp file: ${projectRoot.relativize(tmpPath)}" }
            }
        }
    }

    private fun processPatch(
        path: String,
        replacements: List<Replacement>,
    ): String {
        val resolver = BoundaryPathResolver(projectRoot, denyPatterns)
        val resolved = resolver.resolve(path, createIntent = false)

        if (!resolved.exists()) {
            return "$path: No file found"
        }

        var fileContent = Files.readString(resolved, StandardCharsets.UTF_8)
        val chunksNotFound = mutableListOf<String>()
        val duplicateChunks = mutableListOf<String>()
        val tooShortChunks = mutableListOf<String>()

        for (repl in replacements) {
            val oldPart = repl.oldPart
            val newPart = repl.newPart

            when {
                oldPart.length < PATCH_MIN_CHUNK -> {
                    tooShortChunks.add(oldPart)
                }

                !fileContent.contains(oldPart) -> {
                    chunksNotFound.add(oldPart)
                }

                fileContent.split(oldPart).size - 1 > 1 -> {
                    duplicateChunks.add(oldPart)
                }

                else -> {
                    fileContent = fileContent.replaceFirst(oldPart, newPart)
                }
            }
        }

        // Write file ALWAYS (non-transactional behavior)
        val tmpPath = resolved.resolveSibling("${resolved.fileName}.$PID.${UUID.randomUUID()}.tmp")
        try {
            Files.writeString(tmpPath, fileContent, StandardCharsets.UTF_8)
            Files.move(tmpPath, resolved, StandardCopyOption.ATOMIC_MOVE, StandardCopyOption.REPLACE_EXISTING)
        } finally {
            try {
                Files.deleteIfExists(tmpPath)
            } catch (e: Exception) {
                logger.warn(e) { "Failed to clean up temp file: ${projectRoot.relativize(tmpPath)}" }
            }
        }

        // Build result message
        var message = ""
        if (chunksNotFound.isNotEmpty()) {
            message += "\nChunks not found: \n${formatChunks(chunksNotFound)}"
        }
        if (duplicateChunks.isNotEmpty()) {
            message += "\nDuplicate chunks found: \n${formatChunks(duplicateChunks)}"
        }
        if (tooShortChunks.isNotEmpty()) {
            message += "\nChunks too short: \n${formatChunks(tooShortChunks)}"
        }

        return if (message.isEmpty()) {
            "$path: File successfully edited by chunks"
        } else {
            "$path: File edited with the following results:$message"
        }
    }

    private fun formatChunks(chunks: List<String>): String = chunks.joinToString(" ") { "\n- \"\"\"$it\"\"\"" } + "\n"
}
