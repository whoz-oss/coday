package io.whozoss.agentos.git.core

/**
 * Validation of branch names: the namespace main branch, and branches agents create or push.
 *
 * The rules mirror `git check-ref-format`. They are reimplemented rather than delegated to a
 * subprocess so configuration validation stays pure, fast and available on a host where the git
 * binary is absent — git remains the final authority and rejects anything that slips through.
 */
object GitRefNames {
    private val FORBIDDEN_CHARACTERS = setOf('~', '^', ':', '?', '*', '[', '\\', ' ')

    /**
     * Whether [name] is usable as a branch name (`refs/heads/<name>`).
     *
     * Rejects the cases `git check-ref-format` rejects: empty or `@`, ASCII control characters,
     * the reserved punctuation set, `..`, `@{`, leading/trailing or doubled `/`, a component
     * starting with `.` or ending in `.lock`, a trailing `.`, and a leading `-` (which git accepts
     * but which would be read as an option at a command line).
     */
    fun isValidBranchName(name: String): Boolean {
        if (name.isEmpty() || name == "@") return false
        if (name.startsWith("-")) return false
        if (name.startsWith("/") || name.endsWith("/") || name.contains("//")) return false
        if (name.endsWith(".")) return false
        if (name.contains("..") || name.contains("@{")) return false
        if (name.any { it.code < 0x20 || it.code == 0x7F || it in FORBIDDEN_CHARACTERS }) return false
        return name.split('/').all { component ->
            component.isNotEmpty() && !component.startsWith(".") && !component.endsWith(".lock")
        }
    }

}
