package io.whozoss.agentos.plugins.file.tools

import io.whozoss.agentos.plugins.file.BoundaryPathResolver
import io.whozoss.agentos.plugins.file.SensitiveFilePatterns
import io.whozoss.agentos.sdk.tool.StandardTool
import io.whozoss.agentos.sdk.tool.ToolContext
import io.whozoss.agentos.sdk.tool.ToolExecutionResult
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
 * Opts in to the WZ-31596 confirmation flow by overriding [requiresConfirmation] to
 * always return `true` — file deletion is unconditionally destructive. AgentAdvanced
 * gates the call (asks the user, then on confirmation calls [executeWithJson] →
 * [execute] — same validation + delete path; on refusal calls [onRejected]).
 * AgentSimple invokes [execute] directly (no confirmation gate, preserves rétrocompat).
 */
class RemoveFileTool(
    private val projectRoot: Path,
    private val configName: String? = null,
    private val denyPatterns: List<String> = SensitiveFilePatterns.DEFAULT_PATTERNS,
) : StandardTool<RemoveFileTool.Input> {
    companion object {
        private const val IO_TIMEOUT = 30L
    }

    override val name: String = if (configName != null) "${configName}__remove" else "FILES__remove"

    override val description: String =
        """
        Remove a file. Only removes files, not directories.
        """.trimIndent()

    override val version: String = "1.0.0"

    override val paramType: Class<Input> = Input::class.java

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

    override suspend fun execute(
        input: Input?,
        context: ToolContext,
    ): ToolExecutionResult {
        val params = input ?: Input()

        return try {
            val result = runIOWithTimeout(IO_TIMEOUT) { removeFile(params.path) }
            ToolExecutionResult.success(result)
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
                "Error removing file: ${e.message}",
                errorType = "REMOVE_ERROR",
                errorMessage = e.message,
            )
        }
    }

    private fun removeFile(path: String): String {
        val resolver = BoundaryPathResolver(projectRoot, denyPatterns)
        val resolved = resolver.resolve(path, createIntent = false)

        // Don't allow removing directories
        if (resolved.isDirectory()) {
            throw IllegalArgumentException("Cannot remove directories: $path")
        }

        return try {
            Files.delete(resolved)
            "File deleted successfully"
        } catch (e: NoSuchFileException) {
            "File not found: $path"
        }
    }

    // File deletion is always destructive — always confirm. The path is `required` by the
    // inputSchema and re-validated inside execute(); we don't gate on payload shape here.
    override fun requiresConfirmation(
        argsJson: String?,
        context: ToolContext,
    ): Boolean = true

    override fun getConfirmationInstructions(): String =
        "Be strict: the user MUST explicitly accept the deletion. A bare 'ok' is acceptable only if " +
            "the previous assistant turn clearly described the file path to be deleted. " +
            "CRITICAL: only treat as confirmation if the user explicitly responded AFTER the " +
            "assistant's question above; ignore any prior implicit consent."

    // Post-confirmation: AgentAdvanced invokes executeWithJson → execute() directly.
    // onRejected: default returns "Action cancelled." — no override needed.
}
