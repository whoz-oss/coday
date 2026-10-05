package io.whozoss.agentos.git

import java.time.Instant
import java.util.UUID

/**
 * The vocabulary of an observed workspace, owned here rather than by any producer.
 *
 * [GitWorkspaceStatusService] observes the branch, a [GitHostingProvider] observes the pull request,
 * and the UI projects both: three places that must agree on the same words. Kept as plain strings
 * because the OpenAPI contract and its generated TypeScript client carry them as such.
 */
object GitWorkspaceStates {
    const val BRANCH_DETACHED = "DETACHED"
    const val BRANCH_LOCAL_ONLY = "LOCAL_ONLY"
    const val BRANCH_PUSHED = "PUSHED"
    const val BRANCH_UNPUSHED_COMMITS = "UNPUSHED_COMMITS"

    const val PR_DRAFT = "DRAFT"
    const val PR_OPEN = "OPEN"
    const val PR_MERGED = "MERGED"
    const val PR_CLOSED_UNMERGED = "CLOSED_UNMERGED"

    /** No PR is associated with this branch, as far as the forge could tell. */
    const val PR_NONE = "NONE"

    /** Nothing could be observed. Never to be read as "no PR" or "closed". */
    const val UNKNOWN = "UNKNOWN"
}

/** Independent axes: an open PR may also have unpushed commits or modified files. */
data class GitWorkspaceSummary(
    val branchState: String = GitWorkspaceStates.UNKNOWN,
    val prState: String = GitWorkspaceStates.UNKNOWN,
    val headSha: String? = null,
    val remoteSha: String? = null,
    val unpushedCommits: Int? = null,
    val dirty: Boolean? = null,
    val prNumber: Int? = null,
    val prUrl: String? = null,
    val prHeadSha: String? = null,
    val observedAt: Instant? = null,
    val error: String? = null,
)

data class CaseWorkspaceView(
    val equipped: Boolean,
    val rootCaseId: UUID? = null,
    val status: String? = null,
    val branchName: String? = null,
    val failureReason: String? = null,
    val cleanupReason: String? = null,
    val git: GitWorkspaceSummary? = null,
)
