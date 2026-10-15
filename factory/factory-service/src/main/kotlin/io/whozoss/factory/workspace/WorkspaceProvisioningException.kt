package io.whozoss.factory.workspace

import io.whozoss.factory.error.FactoryException

/**
 * Stable, machine-readable codes of the workspace availability waiter.
 */
object WorkspaceProvisioningCodes {
    /** The workspace stayed `PREPARING` past the wait budget. */
    const val WORKSPACE_PREPARING_TIMEOUT = "WORKSPACE_PREPARING_TIMEOUT"

    /** The workspace preparation reported `FAILED` (or a removal state): never usable as-is. */
    const val WORKSPACE_PROVISIONING_FAILED = "WORKSPACE_PROVISIONING_FAILED"

    /** The workspace is `READY` but carries no worktree path: the run cannot be bound to a directory. */
    const val WORKSPACE_PATH_MISSING = "WORKSPACE_PATH_MISSING"
}

/**
 * Raised when a run's workspace cannot be proven usable.
 *
 * Fail-closed by construction: the waiter throws instead of silently falling back
 * to another directory (`/tmp`, the namespace repo root, the process working
 * directory…). Callers must surface this actionable error, never hide it.
 */
class WorkspaceProvisioningException(
    code: String,
    message: String,
    details: Any? = null,
    cause: Throwable? = null,
) : FactoryException(statusFor(code), code, message, details, cause) {

    companion object {
        private fun statusFor(code: String): Int = when (code) {
            WorkspaceProvisioningCodes.WORKSPACE_PREPARING_TIMEOUT -> 504
            WorkspaceProvisioningCodes.WORKSPACE_PROVISIONING_FAILED -> 502
            else -> 422
        }
    }
}
