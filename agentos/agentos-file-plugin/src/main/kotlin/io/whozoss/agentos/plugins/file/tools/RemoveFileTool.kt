package io.whozoss.agentos.plugins.file.tools

import com.fasterxml.jackson.module.kotlin.jacksonObjectMapper
import io.whozoss.agentos.plugins.file.BoundaryPathResolver
import io.whozoss.agentos.plugins.file.SensitiveFilePatterns
import io.whozoss.agentos.sdk.tool.StandardTool
import io.whozoss.agentos.sdk.tool.ToolContext
import kotlinx.coroutines.TimeoutCancellationException
import java.nio.file.Files
import java.nio.file.NoSuchFileException
import java.nio.file.Path
import kotlin.io.path.isDirectory

/**
 * Remove a file.
 *
 * Only removes files, not directories. Fails if file doesn't exist or is a directory.
 *
 * Opts in to the WZ-31596 confirmation flow: the orchestrator validates the path via
 * [getConfirmationPayload] (no side-effect), asks the user to confirm, then calls
 * [executeWithConfirmation] to actually delete. The direct [execute] path is therefore
 * unreachable in production and exists only to satisfy the [StandardTool] contract —
 * it returns an error so that an orchestrator without confirmation support cannot
 * inadvertently apply a deletion.
 */
class RemoveFileTool(
    private val projectRoot: Path,
    private val configName: String? = null,
    private val denyPatterns: List<String> = SensitiveFilePatterns.DEFAULT_PATTERNS,
) : StandardTool<RemoveFileTool.Input> {
    companion object {
        private val objectMapper = jacksonObjectMapper()
        private const val IO_TIMEOUT = 30L
    }

    override val name: String = if (configName != null) "${configName}__remove" else "FILES__remove"

    override val description: String =
        """
        Remove a file. Only removes files, not directories.
        """.trimIndent()

    override val version: String = "1.0.0"

    override val paramType: Class<Input> = Input::class.java

    override val supportsConfirmation: Boolean = true

    // File deletion is destructive and irreversible — always force an explicit user prompt.
    override val bypassImplicitConsent: Boolean = true

    // language=JSON
    override val inputSchema: String =
        """
        {
            "${'$'}schema": "https://json-schema.org/draft/2020-12/schema",
            "type": "object",
            "properties": {
                "path": {
                    "type": "string",
                    "description": "Relative file path (e.g. \"temp/old.txt\")"
                }
            },
            "required": ["path"],
            "additionalProperties": false
        }
        """.trimIndent()

    data class Input(
        val path: String = "",
    )

    /**
     * Payload computed by [getConfirmationPayload] and round-tripped via JSON through the
     * [PendingConfirmationEvent]. Carries the resolved absolute path so the actual
     * deletion is independent of any later resolver state, plus the original display path
     * for the user-visible confirmation prompt.
     */
    data class PendingRemoval(
        val absolutePath: String = "",
        val displayPath: String = "",
    )

    /**
     * Direct execute is unreachable in production (the orchestrator routes opt-in tools
     * through the confirmation flow). Returns a clear error so that any orchestrator
     * without confirmation support cannot apply a deletion.
     */
    override fun execute(
        input: Input?,
        context: ToolContext,
    ): String = "RemoveFileTool requires user confirmation. This orchestrator does not yet support the confirmation flow."

    override fun requiresConfirmation(
        input: Input?,
        context: ToolContext,
    ): Boolean = !input?.path.isNullOrBlank()

    override fun getConfirmationPayload(
        input: Input?,
        context: ToolContext,
    ): Any {
        val path = input?.path?.takeIf { it.isNotBlank() } ?: throw IllegalArgumentException("path required")
        val resolver = BoundaryPathResolver(projectRoot, denyPatterns)
        val resolved = resolver.resolve(path, createIntent = false)
        if (resolved.isDirectory()) {
            throw IllegalArgumentException("Cannot remove directories: $path")
        }
        return PendingRemoval(absolutePath = resolved.toString(), displayPath = path)
    }

    override fun confirmationLabel(pendingPayload: Any): String {
        val typed = objectMapper.convertValue(pendingPayload, PendingRemoval::class.java)
        return "Delete file ${typed.displayPath}"
    }

    override fun getConfirmationAnalysisInstructions(): String =
        "Be strict: the user MUST explicitly accept the deletion. A bare 'ok' is acceptable only if " +
            "the previous assistant turn clearly described the file path to be deleted. " +
            "CRITICAL: only treat as confirmation if the user explicitly responded AFTER the " +
            "assistant's question above; ignore any prior implicit consent."

    override fun executeWithConfirmation(
        pendingPayload: Any,
        context: ToolContext,
    ): String {
        val typed = objectMapper.convertValue(pendingPayload, PendingRemoval::class.java)
        return try {
            runIOWithTimeout(IO_TIMEOUT) {
                try {
                    Files.delete(Path.of(typed.absolutePath))
                    "File ${typed.displayPath} deleted successfully"
                } catch (e: NoSuchFileException) {
                    "File not found: ${typed.displayPath}"
                }
            }
        } catch (e: TimeoutCancellationException) {
            "Operation timed out after $IO_TIMEOUT seconds"
        } catch (e: Exception) {
            "Error removing file: ${e.message}"
        }
    }

    override fun onRejected(
        pendingPayload: Any,
        userMessage: String,
        context: ToolContext,
    ): String {
        val typed = objectMapper.convertValue(pendingPayload, PendingRemoval::class.java)
        return "Deletion of ${typed.displayPath} was not performed."
    }
}
