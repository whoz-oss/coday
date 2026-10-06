package io.whozoss.agentos.git

import io.swagger.v3.oas.annotations.media.Schema
import java.time.Instant

/**
 * The last observation of a workspace, on independent axes: an open PR may also have unpushed
 * commits or modified files. [GitWorkspaceStatusService] observes the branch and a
 * [GitHostingProvider] the pull request.
 */
@Schema(name = "GitWorkspaceSummary")
data class GitWorkspaceSummary(
    @Schema(description = "Where the workspace branch stands against the remote")
    val branchState: BranchState = BranchState.UNKNOWN,
    @Schema(description = "The pull request of the workspace branch. UNKNOWN never means that there is none")
    val prState: PrState = PrState.UNKNOWN,
    @Schema(description = "Commit checked out in the workspace. Null when it could not be read")
    val headSha: String? = null,
    @Schema(description = "Commit of the remote branch. Null when the branch is not on the remote")
    val remoteSha: String? = null,
    @Schema(description = "Commits of the workspace branch missing from the remote. Null unless the branch is on the remote")
    val unpushedCommits: Int? = null,
    @Schema(description = "Whether the worktree has local changes, untracked files included. Null when it could not be read")
    val dirty: Boolean? = null,
    @Schema(description = "Number of the pull request. Null without one")
    val prNumber: Int? = null,
    @Schema(description = "Web address of the pull request. Null without one")
    val prUrl: String? = null,
    @Schema(description = "Head commit of the pull request. Null without one")
    val prHeadSha: String? = null,
    @Schema(description = "When this observation started")
    val observedAt: Instant? = null,
    @Schema(description = "Why the observation failed. Null when it succeeded. Never a secret")
    val error: String? = null,
)
