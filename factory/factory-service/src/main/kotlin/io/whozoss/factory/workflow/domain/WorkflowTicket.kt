package io.whozoss.factory.workflow.domain

/**
 * Shared ticket identifier validation and branch-component normalization for
 * both Jira-style keys (`ABC-123`) and GitHub issue references (`#123`, `123`, `owner/repo#123`).
 */
object WorkflowTicket {

    /**
     * Pattern matching a Jira project key (`ABC-123`) or a GitHub issue reference
     * (`#123`, `123`, `owner/repo#123`), up to 64 characters.
     */
    const val PATTERN_STRING =
        "^(?:(?:#|[A-Za-z0-9][A-Za-z0-9._-]{0,38}/[A-Za-z0-9._-]{1,48}#)?[1-9][0-9]{0,11}|[A-Z][A-Z0-9]{1,15}-[1-9][0-9]{0,11})$"

    val PATTERN = Regex(PATTERN_STRING)

    /**
     * Broader workstream start pattern preserving backwards compatibility with
     * alphanumeric ticket slugs used by existing workstream callers while also
     * accepting `#123` and `owner/repo#123` GitHub issue references.
     */
    val WORKSTREAM_START_PATTERN = Regex("^(?:#[1-9][0-9]{0,11}|[A-Za-z0-9][A-Za-z0-9._:/#-]{0,63})$")

    private val GITHUB_ISSUE_SUFFIX = Regex(
        "^(?:#|[A-Za-z0-9][A-Za-z0-9._-]{0,38}/[A-Za-z0-9._-]{1,48}#)([1-9][0-9]{0,11})$",
    )

    fun isTrustedTicket(value: String?): Boolean = value != null && PATTERN.matches(value)

    /**
     * Computes the authoritative Factory branch name (`work/forge/<sanitized-component>`)
     * from a trusted ticket or the fallback [workflowId].
     */
    fun branchName(trustedTicket: String?, fallbackTicket: String? = null, workflowId: String): String {
        val rawComponent = trustedTicket?.takeIf(::isTrustedTicket)
            ?: fallbackTicket?.takeIf(::isTrustedTicket)
            ?: workflowId
        val normalized = GITHUB_ISSUE_SUFFIX.find(rawComponent)?.groupValues?.get(1) ?: rawComponent
        val sanitized = normalized.replace(Regex("[^A-Za-z0-9._-]"), "-").trim('-', '.').take(100)
        return "work/forge/$sanitized"
    }
}
