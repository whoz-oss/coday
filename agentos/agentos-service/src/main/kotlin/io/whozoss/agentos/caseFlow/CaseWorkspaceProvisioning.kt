package io.whozoss.agentos.caseFlow

/**
 * Hook letting an optional capability react to the creation of a case.
 *
 * Declared here, implemented in `io.whozoss.agentos.git`, for the same reason `SubCaseManager`
 * exists: the Git workspace layer needs to read cases, so having the case layer depend on it
 * directly would close a cycle. The case layer depends on this narrow contract instead, and knows
 * nothing about Git.
 *
 * Installed only with `agentos.git.workspaces.enabled`; without it, case creation is unchanged.
 * Implementations must be cheap for the common path: a namespace with no Git association does no
 * work at all here. They may refuse a creation they cannot honour, such as a family that should be
 * equipped under settings that are no longer valid, rather than create it in a state that no retry
 * can repair.
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
}
