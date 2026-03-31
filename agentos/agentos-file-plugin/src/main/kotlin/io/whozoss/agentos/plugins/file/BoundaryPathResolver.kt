package io.whozoss.agentos.plugins.file

import java.nio.file.Files
import java.nio.file.LinkOption
import java.nio.file.NoSuchFileException
import java.nio.file.Path
import java.nio.file.attribute.BasicFileAttributes
import kotlin.io.path.absolute
import kotlin.io.path.exists
import kotlin.io.path.isDirectory
import kotlin.io.path.name
import kotlin.io.path.pathString

/**
 * File scope identifiers for path resolution.
 */
enum class FileScope {
    PROJECT,
    EXCHANGE,
}

/**
 * Result of path resolution with security validation.
 */
data class ResolvedPath(
    val absolutePath: Path,
    val scope: FileScope,
    val relativePath: String,
)

/**
 * File prefixes for scoped path resolution.
 */
object FilePrefixes {
    const val PROJECT = "project://"
    const val EXCHANGE = "exchange://"
}

/**
 * Default deny-list of sensitive file patterns.
 * These patterns block access to common credential and secret files.
 */
object SensitiveFilePatterns {
    val DEFAULT_PATTERNS =
        listOf(
            ".env",
            ".env.*",
            "credentials.json",
            "*.key",
            "*.pem",
            "token.json",
            "auth-profiles.json",
            "*.p12",
            "*.pfx",
            "id_rsa",
            "id_dsa",
            "id_ecdsa",
            "id_ed25519",
        )
}

/**
 * Boundary-safe path resolver with segment-by-segment traversal.
 *
 * This resolver validates paths
 * by traversing each segment lexically and checking for symlinks at every step.
 * This prevents TOCTOU (Time-of-Check-Time-of-Use) symlink attacks where a symlink
 * could be created between validation and use.
 *
 * Security features:
 * - Segment-by-segment traversal with lstat() at each step
 * - Symlink resolution with isPathInside() validation
 * - Deny-list for sensitive file patterns
 * - Path traversal attack prevention
 * - Null byte and URL encoding rejection
 *
 * @property rootPath The root directory for boundary enforcement (not necessarily canonical)
 * @property denyPatterns List of glob-style patterns to block (e.g., "*.key", ".env")
 */
class BoundaryPathResolver(
    private val rootPath: Path,
    private val denyPatterns: List<String> = SensitiveFilePatterns.DEFAULT_PATTERNS,
) {
    private val rootCanonical = rootPath.toRealPath()

    /**
     * Resolve a relative path with boundary enforcement and symlink validation.
     *
     * This method performs a lexical traversal of the path, checking each segment
     * for symlinks and validating that the target stays within the root boundary.
     *
     * @param relativePath Relative path (without prefix) to resolve
     * @param createIntent If true, allows missing path segments (for file creation)
     * @return Canonicalized absolute path that is guaranteed to be within the root
     * @throws IllegalArgumentException if path escapes boundary, contains dangerous sequences,
     *         matches deny-list, or doesn't exist (when createIntent=false)
     */
    fun resolve(
        relativePath: String,
        createIntent: Boolean = false,
    ): Path {
        // 1. Reject dangerous sequences
        if (relativePath.contains('\u0000') || relativePath.contains("%00") ||
            relativePath.contains("%2F", ignoreCase = true) ||
            relativePath.contains("%5C", ignoreCase = true)
        ) {
            throw IllegalArgumentException("Invalid path: illegal characters detected in $relativePath")
        }

        // 2. Normalize and validate path traversal
        val normalized = Path.of(relativePath).normalize()
        val normalizedStr = normalized.pathString

        // Check for path traversal attempts
        if (normalizedStr.startsWith("..") || normalizedStr.contains("${Path.of("").fileSystem.separator}..")) {
            throw IllegalArgumentException("Invalid path: path traversal not allowed ($relativePath)")
        }

        // 3. Split into segments
        val segments = normalized.map { it.pathString }

        // Empty path resolves to root
        if (segments.isEmpty()) {
            return rootCanonical
        }

        // 4. Validate segment lengths
        segments.forEach { segment ->
            if (segment.length > 255) {
                throw IllegalArgumentException("Invalid path: segment exceeds 255 characters in $relativePath")
            }
        }

        // 5. Segment-by-segment traversal with symlink validation
        var canonicalCursor = rootCanonical
        var lexicalCursor = rootPath.absolute().normalize()

        for ((idx, segment) in segments.withIndex()) {
            val isLast = idx == segments.size - 1
            lexicalCursor = lexicalCursor.resolve(segment)

            // Use lstat to detect symlinks without following them
            val lstat =
                try {
                    Files.readAttributes(lexicalCursor, BasicFileAttributes::class.java, LinkOption.NOFOLLOW_LINKS)
                } catch (e: NoSuchFileException) {
                    // File doesn't exist
                    if (createIntent) {
                        // For create intent, append remaining segments to canonical cursor
                        val missingSuffix = segments.drop(idx)
                        canonicalCursor = missingSuffix.fold(canonicalCursor) { acc, s -> acc.resolve(s) }
                        break
                    } else {
                        throw IllegalArgumentException("Path does not exist: $relativePath")
                    }
                }

            // Handle symlinks: resolve and validate target
            if (lstat.isSymbolicLink) {
                val linkTarget = Files.readSymbolicLink(lexicalCursor)
                val linkAbsolute = lexicalCursor.parent.resolve(linkTarget).normalize()

                // Resolve symlink chain completely
                val linkCanonical =
                    try {
                        linkAbsolute.toRealPath()
                    } catch (e: NoSuchFileException) {
                        throw IllegalArgumentException("Symlink target does not exist: $lexicalCursor -> $linkTarget")
                    }

                // Validate that symlink target stays within root
                if (!linkCanonical.startsWith(rootCanonical)) {
                    throw IllegalArgumentException(
                        "Symlink escapes boundary: $lexicalCursor -> $linkCanonical (root: $rootCanonical)",
                    )
                }

                // Update both cursors with resolved symlink target
                canonicalCursor = linkCanonical
                lexicalCursor = linkCanonical
            } else {
                // Regular file/directory: advance canonical cursor
                canonicalCursor = canonicalCursor.resolve(segment)
            }
        }

        // 6. Final boundary check
        if (!canonicalCursor.startsWith(rootCanonical)) {
            throw IllegalArgumentException("Invalid path: path escapes root boundary ($relativePath)")
        }

        // 7. Deny-list validation
        val fileName = canonicalCursor.name
        for (pattern in denyPatterns) {
            if (matchesPattern(fileName, pattern)) {
                throw IllegalArgumentException("Access denied: file matches sensitive pattern ($pattern)")
            }
        }

        return canonicalCursor
    }

    /**
     * Simple glob pattern matching.
     *
     * Supports:
     * - "*.ext" (suffix match)
     * - "prefix.*" (prefix match)
     * - "*substring*" (contains match)
     * - "exact" (exact match)
     */
    private fun matchesPattern(
        fileName: String,
        pattern: String,
    ): Boolean =
        when {
            pattern.startsWith("*") && pattern.endsWith("*") ->
                fileName.contains(pattern.trim('*'))
            pattern.startsWith("*") ->
                fileName.endsWith(pattern.removePrefix("*"))
            pattern.endsWith("*") ->
                fileName.startsWith(pattern.removeSuffix("*"))
            else ->
                fileName == pattern
        }
}

/**
 * High-level file path resolver with prefix handling.
 *
 * This is the main entry point for path resolution in file tools.
 * It strips the scope prefix (project:// or exchange://), looks up the
 * root path from fileRoots, and delegates to BoundaryPathResolver for
 * security-enforced resolution.
 *
 * @param filePath Full path with prefix (e.g., "project://src/main.ts")
 * @param fileRoots Map of scope names to root paths (from ToolExecutionContext)
 * @param createIntent If true, allows missing path segments (for file creation)
 * @param denyPatterns Custom deny-list patterns (defaults to SensitiveFilePatterns.DEFAULT_PATTERNS)
 * @return ResolvedPath with absolutePath, scope, and relativePath
 * @throws IllegalArgumentException if prefix is invalid, scope not found, or path validation fails
 */
fun resolveFilePath(
    filePath: String,
    fileRoots: Map<String, Path>,
    createIntent: Boolean = false,
    denyPatterns: List<String> = SensitiveFilePatterns.DEFAULT_PATTERNS,
): ResolvedPath {
    // 1. Determine scope and extract relative path
    val (scope, scopeKey, relativePath) =
        when {
            filePath.startsWith(FilePrefixes.PROJECT) ->
                Triple(FileScope.PROJECT, "project", filePath.removePrefix(FilePrefixes.PROJECT))
            filePath.startsWith(FilePrefixes.EXCHANGE) ->
                throw IllegalArgumentException(
                    "exchange:// is not supported in this version. Use project:// prefix.",
                )
            else ->
                throw IllegalArgumentException(
                    "File path must start with \"${FilePrefixes.PROJECT}\" or \"${FilePrefixes.EXCHANGE}\". " +
                        "Use searchFiles to find files.",
                )
        }

    // 2. Lookup root path
    val rootPath =
        fileRoots[scopeKey]
            ?: throw IllegalArgumentException("No root configured for scope: $scopeKey")

    // 3. Use BoundaryPathResolver for secure resolution
    val resolver = BoundaryPathResolver(rootPath, denyPatterns)
    val resolved = resolver.resolve(relativePath, createIntent)

    return ResolvedPath(
        absolutePath = resolved,
        scope = scope,
        relativePath = relativePath,
    )
}
