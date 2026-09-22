package io.whozoss.agentos.plugins.file.tools

import io.whozoss.agentos.plugins.file.BoundaryPathResolver
import io.whozoss.agentos.plugins.file.SensitiveFilePatterns
import io.whozoss.agentos.sdk.tool.StandardTool
import io.whozoss.agentos.sdk.tool.ToolContext
import io.whozoss.agentos.sdk.tool.ToolExecutionResult
import kotlinx.coroutines.TimeoutCancellationException
import java.nio.file.AtomicMoveNotSupportedException
import java.nio.file.FileAlreadyExistsException
import java.nio.file.Files
import java.nio.file.LinkOption
import java.nio.file.NoSuchFileException
import java.nio.file.Path
import java.nio.file.StandardCopyOption
import kotlin.io.path.pathString

/**
 * Move or rename a file or directory within the configured root directory.
 *
 * Fails if source doesn't exist or destination already exists.
 */
class MoveFileTool(
    private val projectRoot: Path,
    private val configName: String? = null,
    private val denyPatterns: List<String> = SensitiveFilePatterns.DEFAULT_PATTERNS,
) : StandardTool<MoveFileTool.Input> {
    companion object {
        private const val IO_TIMEOUT = 30L
    }

    override val name: String = if (configName != null) "${configName}__moveFile" else "FILES__moveFile"

    override val description: String =
        """
        Move or rename a file or directory. Fails if the source does not exist, the destination already exists, or any moved entry is protected.
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
                    "description": "Source relative file or directory path (e.g. \"old/path.ts\")"
                },
                "to": {
                    "type": "string",
                    "description": "Destination relative file or directory path (e.g. \"new/path.ts\")"
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

    override suspend fun execute(
        input: Input?,
        context: ToolContext,
    ): ToolExecutionResult {
        val params = input ?: Input()

        return try {
            val result = runIOWithTimeout(IO_TIMEOUT) { moveFile(params.from, params.to) }
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
                "Error moving file: ${e.message}",
                errorType = "MOVE_ERROR",
                errorMessage = e.message,
            )
        }
    }

    /** Validate the whole moved tree before creating destination parents or changing any files. */
    private fun validateDirectoryMove(
        from: Path,
        to: Path,
        resolver: BoundaryPathResolver,
    ) {
        val root = resolver.resolve("")
        require(from != root) { "Cannot move the configured root directory" }
        require(!to.startsWith(from)) { "Cannot move a directory inside itself" }
        Files.walk(from).use { entries ->
            entries.forEach { entry ->
                // The resolver checks every segment and follows symlinks only within the boundary.
                resolver.resolve(root.relativize(entry).pathString)
                val destination = to.resolve(from.relativize(entry))
                resolver.resolve(root.relativize(destination).pathString, createIntent = true)
                if (Files.isSymbolicLink(entry)) {
                    val actualTarget = entry.toRealPath()
                    require(actualTarget.startsWith(root)) { "Symlink escapes boundary: $entry" }
                    resolver.resolve(root.relativize(actualTarget).pathString)
                    validateRelocatedLink(entry, destination, from, to, root, resolver)
                }
            }
        }
    }

    /** Resolve relative targets in the future tree without collapsing symlink/.. components. */
    private fun validateRelocatedLink(
        entry: Path,
        destination: Path,
        from: Path,
        to: Path,
        root: Path,
        resolver: BoundaryPathResolver,
    ) {
        val target = Files.readSymbolicLink(entry)
        // Absolute links retain the target already checked above, including filesystem aliases.
        if (target.isAbsolute) return
        val pending = java.util.ArrayDeque(target.map { it.pathString })
        var cursor = destination.parent
        var linksFollowed = 0
        while (pending.isNotEmpty()) {
            val segment = pending.removeFirst()
            if (segment.isEmpty() || segment == ".") continue
            cursor = if (segment == "..") cursor.parent else cursor.resolve(segment)
            require(cursor != null && cursor.startsWith(root)) { "A moved symlink would escape the configured root" }
            resolver.resolve(root.relativize(cursor).pathString, createIntent = true)
            val physical =
                when {
                    cursor.startsWith(to) -> from.resolve(to.relativize(cursor))
                    cursor.startsWith(from) -> null // The old location disappears after the move.
                    else -> cursor
                }
            if (physical != null && Files.isSymbolicLink(physical)) {
                require(++linksFollowed <= 40) { "Too many symlinks in the moved target" }
                val nextTarget = Files.readSymbolicLink(physical)
                if (nextTarget.isAbsolute) {
                    cursor = physical.toRealPath()
                    require(cursor.startsWith(root)) { "A moved symlink would escape the configured root" }
                    resolver.resolve(root.relativize(cursor).pathString)
                } else {
                    cursor = cursor.parent
                    nextTarget.map { it.pathString }.asReversed().forEach(pending::addFirst)
                }
            }
        }
    }

    private fun moveFile(
        from: String,
        to: String,
    ): String {
        val resolver = BoundaryPathResolver(projectRoot, denyPatterns)
        val resolvedFrom = resolver.resolve(from, createIntent = false)
        val resolvedTo = resolver.resolve(to, createIntent = true)
        if (Files.isDirectory(resolvedFrom)) {
            validateDirectoryMove(resolvedFrom, resolvedTo, resolver)
        }

        // Check destination doesn't exist
        if (Files.exists(resolvedTo, LinkOption.NOFOLLOW_LINKS)) {
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
}
