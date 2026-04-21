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
 * Accepts plain relative paths (e.g. "src/main.ts", "a/b/c.txt").
 * The configured [rootPath] is an internal implementation detail — callers
 * and the LLM agent never see it.
 *
 * Security features:
 * - Segment-by-segment traversal with lstat() at each step
 * - Symlink resolution with boundary validation
 * - Deny-list for sensitive file patterns
 * - Path traversal attack prevention
 * - Null byte and URL encoding rejection
 *
 * @property rootPath The root directory for boundary enforcement
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
     * @param relativePath Relative path (e.g. "src/main.ts" or "dir/") to resolve
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

}
