package io.whozoss.agentos.plugins.kotlin

import io.whozoss.agentos.sdk.util.SensitiveFileDetector
import org.eclipse.lsp4j.Position
import java.net.URI
import java.nio.file.Files
import java.nio.file.Path

/**
 * Single place where file paths and `file://` URIs are converted, and where a location is displayed.
 *
 * Agents see project relative paths and 1-based lines (like the file tools and grep);
 * the LSP protocol sees absolute URIs and 0-based positions.
 *
 * Every path an agent supplies goes through [checked], which keeps the tools inside the project.
 */
internal class ProjectPaths(
    root: Path,
) {
    private val root: Path = root.toAbsolutePath().normalize()

    /** The real path of the root, so that a link below it can be compared; the root itself when it does not exist. */
    private val realRoot: Path by lazy { runCatching { this.root.toRealPath() }.getOrDefault(this.root) }

    /** Absolute normalized path of [filePath], which may be absolute or relative to the project root. */
    fun absolute(filePath: String): String = root.resolve(filePath).normalize().toString()

    /**
     * [absolute] of [filePath], once it is known to stay inside the project and to name nothing sensitive.
     *
     * The check is lexical, then repeated on the real path of the deepest existing ancestor, so that a
     * symbolic link cannot lead out of the project, not even for a file that does not exist yet.
     * A segment named like a sensitive file, or `.git`, is refused.
     *
     * @throws IllegalArgumentException when [filePath] is outside the project, resolves outside it, or is sensitive
     */
    fun checked(filePath: String): String {
        val resolved = root.resolve(filePath).normalize()
        require(resolved.startsWith(root)) { "'$filePath' is outside the project" }
        require(root.relativize(resolved).none { isForbidden(segment = it.toString()) }) {
            "Access denied: '$filePath' is a sensitive path"
        }
        val anchor = generateSequence(resolved) { it.parent }.takeWhile { it.startsWith(root) }.firstOrNull { Files.exists(it) }
        require(anchor == null || anchor.toRealPath().startsWith(realRoot)) { "'$filePath' resolves outside the project" }
        return resolved.toString()
    }

    /** URI of [filePath] as announced to the server by [LspConnection.syncFile]. */
    fun uriOf(filePath: String): String = Path.of(absolute(filePath = filePath)).toUri().toString()

    /** Absolute path of a `file:` [uri], `null` for any other scheme (e.g. `jar:`). */
    fun filePathOf(uri: String): String? =
        runCatching { URI(uri) }
            .getOrNull()
            ?.takeIf { it.scheme == FILE_SCHEME }
            ?.path

    /** Path relative to the project root, `null` when [uri] is not a file inside the project. */
    fun relativeOf(uri: String): String? =
        filePathOf(uri = uri)
            ?.let { Path.of(it).normalize() }
            ?.takeIf { it.startsWith(root) }
            ?.let { root.relativize(it).toString() }

    /** Whether [uri] is a file inside the project. */
    fun isProjectUri(uri: String): Boolean = relativeOf(uri = uri) != null

    /** Short name of [filePath] for messages: relative to the project when inside it. */
    fun displayPath(filePath: String): String = relativeOf(uri = uriOf(filePath = filePath)) ?: filePath

    /** `relative/path.kt:42` (1-based line), or `[library] uri` outside the project. */
    fun place(
        uri: String,
        position: Position,
    ): String = relativeOf(uri = uri)?.let { "$it:${position.line + 1}" } ?: "[library] $uri"

    private fun isForbidden(segment: String): Boolean =
        segment.isNotEmpty() && (segment == GIT_DIRECTORY || SensitiveFileDetector.isSensitive(segment))

    private companion object {
        const val FILE_SCHEME = "file"
        const val GIT_DIRECTORY = ".git"
    }
}
