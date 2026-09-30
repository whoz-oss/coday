package io.whozoss.agentos.git

import java.time.Instant
import java.util.UUID

/** Independent axes: an open PR may also have unpushed commits or modified files. */
data class GitWorkspaceSummary(
    val branchState: String = "UNKNOWN",
    val prState: String = "UNKNOWN",
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
