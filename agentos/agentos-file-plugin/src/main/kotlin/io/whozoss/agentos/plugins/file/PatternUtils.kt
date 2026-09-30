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
internal fun matchesPattern(fileName: String, pattern: String, ignoreCase: Boolean = false): Boolean = when {
    pattern.startsWith("*") && pattern.endsWith("*") -> fileName.contains(pattern.trim('*'), ignoreCase = ignoreCase)
    pattern.startsWith("*") -> fileName.endsWith(pattern.removePrefix("*"), ignoreCase = ignoreCase)
    pattern.endsWith("*") -> fileName.startsWith(pattern.removeSuffix("*"), ignoreCase = ignoreCase)
    else -> fileName.equals(pattern, ignoreCase = ignoreCase)
}
