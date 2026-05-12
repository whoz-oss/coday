package io.whozoss.agentos.redirect

/**
 * Converts a glob pattern (only `*` as wildcard) to a case-insensitive [Regex].
 *
 * All regex metacharacters in the pattern are escaped before substituting `*`
 * with `.*`. The resulting regex is anchored (`^...$`) so it matches the full
 * string, not a substring.
 *
 * Supported wildcard:
 * - `*` — matches any sequence of characters (including empty)
 *
 * Examples:
 * - `"*"` matches any name
 * - `"Github*"` matches `"GithubAgent"`, `"github-issues"`, etc.
 * - `"Jira*"` matches `"JiraPlugin"`, `"jira"`, etc.
 */
internal fun globToRegex(pattern: String): Regex {
    val escaped = pattern
        .replace("\\", "\\\\")
        .replace(".", "\\.")
        .replace("[", "\\[")
        .replace("]", "\\]")
        .replace("{", "\\{")
        .replace("}", "\\}")
        .replace("(", "\\(")
        .replace(")", "\\)")
        .replace("^", "\\^")
        .replace("$", "\\$")
        .replace("|", "\\|")
        .replace("*", ".*")
    return Regex("^$escaped$", RegexOption.IGNORE_CASE)
}
