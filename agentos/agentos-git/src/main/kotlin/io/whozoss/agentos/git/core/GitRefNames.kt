package io.whozoss.agentos.git.core

/**
 * Validation of the namespace main branch name.
 *
 * An allow-list, stricter than `git check-ref-format`. It is checked here rather than by a
 * subprocess so configuration validation stays pure, fast and available on a host where the git
 * binary is absent. Git remains the final authority and rejects anything that slips through.
 */
object GitRefNames {
    /** A path segment: letters, digits, underscore, minus and dot only. */
    private val SEGMENT = Regex("[A-Za-z0-9_.-]+")

    /**
     * Whether [name] is usable as a branch name (`refs/heads/<name>`).
     *
     * Allow-list: segments of letters, digits, `_`, `-` and `.`, separated by single `/`. On top of
     * it, the `git check-ref-format` rules the allow-list does not cover: no `..`, no segment
     * starting with `.` or ending with `.lock`, no trailing `.`, and no leading `-` (git accepts it,
     * but a command line would read it as an option). Stricter than Git on purpose: names with other
     * characters are refused even when Git would take them.
     */
    fun isValidBranchName(name: String): Boolean {
        if (name.startsWith("-") || name.endsWith(".") || name.contains("..")) return false
        return name.split('/').all { segment ->
            segment.matches(SEGMENT) && !segment.startsWith(".") && !segment.endsWith(".lock")
        }
    }
}
