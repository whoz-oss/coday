package io.whozoss.agentos.caseFlow

/**
 * Hook letting an optional capability react to the creation of a case.
 *
 * Declared here, implemented in `io.whozoss.agentos.git`, for the same reason `SubCaseManager`
 * exists: the Git workspace layer needs to read cases, so having the case layer depend on it
 * directly would close a cycle. The case layer depends on this narrow contract instead, and knows
 * nothing about Git.
 *
 * Implementations must be cheap for the common path and must not fail case creation for a reason
 * that is not the caller's fault: a namespace with no Git association does no work at all here.
 */
interface CaseWorkspaceProvisioning {
    /** Coordinate configuration before any case write can lock the namespace in the database. */
    fun <T> aroundCreation(case: Case, action: () -> T): T = action()

    /**
     * Called once a case has been persisted.
     *
     * The implementation decides whether this case starts a family that needs a workspace. It
     * never provisions anything synchronously: it records the intent, leaving the slow part
     * (clone, branch, worktree, setup) to the provisioner.
     */
    fun onCaseCreated(case: Case)

    companion object {
        /** Used when no capability is wired, which is every deployment without the Git feature. */
        val NOOP: CaseWorkspaceProvisioning =
            object : CaseWorkspaceProvisioning {
                override fun onCaseCreated(case: Case) = Unit
            }
    }
}
