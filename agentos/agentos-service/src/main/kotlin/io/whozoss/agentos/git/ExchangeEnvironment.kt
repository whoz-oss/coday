package io.whozoss.agentos.git

/** What the Files panel shows about a case's workspace: its Git state and local changes. */
data class ExchangeEnvironment(
    val equipped: Boolean,
    val status: String? = null,
    val path: String? = null,
    val branch: String? = null,
    val changes: ExchangeGitChanges? = null,
    val git: GitWorkspaceSummary? = null,
    val error: String? = null,
)
