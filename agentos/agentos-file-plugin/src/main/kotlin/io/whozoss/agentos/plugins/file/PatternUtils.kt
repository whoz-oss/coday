package io.whozoss.agentos.plugins.file

/**
 * Simple glob pattern matching.
 *
 * Supports:
 * - "*.ext" (suffix match)
 * - "prefix.*" (prefix match)
 * - "*substring*" (contains match)
 * - "exact" (exact match)
 */
internal fun matchesPattern(fileName: String, pattern: String): Boolean = when {
    pattern.startsWith("*") && pattern.endsWith("*") -> fileName.contains(pattern.trim('*'))
    pattern.startsWith("*") -> fileName.endsWith(pattern.removePrefix("*"))
    pattern.endsWith("*") -> fileName.startsWith(pattern.removeSuffix("*"))
    else -> fileName == pattern
}
