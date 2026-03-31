package io.whozoss.agentos.plugins.file.tools

import com.fasterxml.jackson.annotation.JsonSubTypes
import com.fasterxml.jackson.annotation.JsonTypeInfo
import com.fasterxml.jackson.module.kotlin.jacksonObjectMapper
import io.whozoss.agentos.plugins.file.resolveFilePath
import io.whozoss.agentos.sdk.tool.ContextAwareTool
import io.whozoss.agentos.sdk.tool.ToolExecutionContext
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.TimeoutCancellationException
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeout
import java.nio.charset.StandardCharsets
import java.nio.file.Files
import java.nio.file.StandardCopyOption
import java.util.UUID
import kotlin.io.path.exists
import kotlin.io.path.fileSize
import kotlin.time.Duration.Companion.seconds

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
class EditFilesTool : ContextAwareTool<EditFilesTool.Input> {
    companion object {
        private val objectMapper = jacksonObjectMapper()
        private const val IO_TIMEOUT = 30L
        private const val WRITE_SIZE_THRESHOLD = 64 * 1024 // 64 KB
        private const val PATCH_MIN_CHUNK = 15
        private val PID = ProcessHandle.current().pid()
    }

    override val name: String = "FILES__editFiles"

    override val description: String =
        """
        Edit one or more files in a single tool call. Each edit targets a specific file and specifies an operation:
        "write" replaces the entire file content (or creates it), "patch" replaces specific chunks within an existing file.
        Edits are executed independently: a failure on one file does not prevent others from being processed.
        File paths must start with "project://".
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
                                "description": "File path with prefix (e.g. \"project://src/main.ts\")"
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
        val content: String,
    ) : Edit

    data class PatchEdit(
        override val path: String,
        val replacements: List<Replacement>,
    ) : Edit

    data class Replacement(
        val oldPart: String,
        val newPart: String,
    )

    data class Input(
        val edits: List<Map<String, Any>> = emptyList(),
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

            if (params.edits.isEmpty()) {
                return "No edits provided."
            }

            kotlinx.coroutines.runBlocking {
                withTimeout(IO_TIMEOUT.seconds) {
                    withContext(Dispatchers.IO) {
                        processEdits(params.edits, context)
                    }
                }
            }
        } catch (e: TimeoutCancellationException) {
            createErrorResponse("Operation timed out after ${IO_TIMEOUT} seconds")
        } catch (e: Exception) {
            createErrorResponse("Error processing edits: ${e.message}")
        }
    }

    private fun processEdits(
        edits: List<Map<String, Any>>,
        context: ToolExecutionContext,
    ): String {
        val results = mutableListOf<String>()

        for (editMap in edits) {
            val path = editMap["path"] as? String ?: continue
            val operation = editMap["operation"] as? String

            val result =
                try {
                    when (operation) {
                        "write" -> {
                            val content = editMap["content"] as? String ?: ""
                            processWrite(path, content, context)
                        }
                        "patch" -> {
                            @Suppress("UNCHECKED_CAST")
                            val replacements = editMap["replacements"] as? List<Map<String, Any>> ?: emptyList()
                            processPatch(path, replacements, context)
                        }
                        else -> "$path: Unknown operation"
                    }
                } catch (e: Exception) {
                    "$path: Error — ${e.message}"
                }

            results.add(result)
        }

        return results.joinToString("\n")
    }

    private fun processWrite(
        path: String,
        content: String,
        context: ToolExecutionContext,
    ): String {
        val resolved = resolveFilePath(path, context.fileRoots, createIntent = true)

        // Check threshold on EXISTING files
        if (resolved.absolutePath.exists()) {
            val existingSize = resolved.absolutePath.fileSize()
            if (existingSize > WRITE_SIZE_THRESHOLD) {
                return "$path: File full write not accepted: file exceeds the size threshold of ${WRITE_SIZE_THRESHOLD / 1024}kB. Use patch operation instead."
            }
        }

        // Atomic write with cleanup
        val tmpPath = resolved.absolutePath.resolveSibling("${resolved.absolutePath.fileName}.${PID}.${UUID.randomUUID()}.tmp")
        return try {
            // Create parent directories
            resolved.absolutePath.parent?.let { Files.createDirectories(it) }

            Files.writeString(tmpPath, content, StandardCharsets.UTF_8)
            Files.move(tmpPath, resolved.absolutePath, StandardCopyOption.ATOMIC_MOVE, StandardCopyOption.REPLACE_EXISTING)
            "$path: File write success"
        } finally {
            try {
                Files.deleteIfExists(tmpPath)
            } catch (_: Exception) {
                // Ignore cleanup errors
            }
        }
    }

    private fun processPatch(
        path: String,
        replacements: List<Map<String, Any>>,
        context: ToolExecutionContext,
    ): String {
        val resolved = resolveFilePath(path, context.fileRoots, createIntent = false)

        if (!resolved.absolutePath.exists()) {
            return "$path: No file found"
        }

        var fileContent = Files.readString(resolved.absolutePath, StandardCharsets.UTF_8)
        val chunksNotFound = mutableListOf<String>()
        val duplicateChunks = mutableListOf<String>()
        val tooShortChunks = mutableListOf<String>()

        for (repl in replacements) {
            val oldPart = repl["oldPart"] as? String ?: continue
            val newPart = repl["newPart"] as? String ?: continue

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
        val tmpPath = resolved.absolutePath.resolveSibling("${resolved.absolutePath.fileName}.${PID}.${UUID.randomUUID()}.tmp")
        try {
            Files.writeString(tmpPath, fileContent, StandardCharsets.UTF_8)
            Files.move(tmpPath, resolved.absolutePath, StandardCopyOption.ATOMIC_MOVE, StandardCopyOption.REPLACE_EXISTING)
        } finally {
            try {
                Files.deleteIfExists(tmpPath)
            } catch (_: Exception) {
                // Ignore cleanup errors
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

    private fun createErrorResponse(message: String): String = message
}
