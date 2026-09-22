package io.whozoss.agentos.caseFlow

import java.util.UUID

/**
 * Decides whether an agent run may start for a case right now.
 *
 * Declared here and implemented in `io.whozoss.agentos.git`, the same seam
 * [CaseWorkspaceProvisioning] uses, so the case layer stays unaware of Git.
 *
 * A gate that says no is **not** an error: the case keeps its pending state and its message stays
 * persisted, waiting. Something else is responsible for calling [CaseService.resumeIfPending] once
 * the obstacle clears.
 */
interface CaseLaunchGate {
    /**
     * Whether a run may be launched for [caseId].
     *
     * Unavailable resources defer execution; their status endpoint exposes the failure and retry.
     * An error must never permit execution in an unrelated directory.
     */
    fun canLaunch(caseId: UUID): Boolean

    companion object {
        /** Used when no capability gates runs, which is every deployment without Git workspaces. */
        val ALWAYS: CaseLaunchGate =
            object : CaseLaunchGate {
                override fun canLaunch(caseId: UUID): Boolean = true
            }
    }
}
