package io.whozoss.factory.workspace

import io.whozoss.factory.error.FactoryException

/**
 * Stable, machine-readable codes of the read-only workspace pre-check.
 *
 * Each code maps to the exact prerequisite that is missing, so the caller (and
 * the operator reading the error envelope) can repair it without guessing.
 */
object WorkspacePrecheckCodes {
    const val NAMESPACE_INACCESSIBLE = "NAMESPACE_INACCESSIBLE"
    const val GIT_WORKSPACES_DISABLED = "GIT_WORKSPACES_DISABLED"
    const val GIT_CONFIG_MISSING = "GIT_CONFIG_MISSING"
    const val AUTO_WORKTREE_DISABLED = "AUTO_WORKTREE_DISABLED"
    const val WORKSTREAM_UNRESOLVABLE = "WORKSTREAM_UNRESOLVABLE"
    const val REQUIRED_AGENTS_MISSING = "REQUIRED_AGENTS_MISSING"
}

/**
 * A workspace prerequisite failed the read-only pre-check.
 *
 * The pre-check NEVER repairs and NEVER mutates configuration: it only reports a
 * missing prerequisite with an actionable [details] payload. The [errorCode] is
 * one of [WorkspacePrecheckCodes], and [statusCode] is chosen so the HTTP
 * envelope is meaningful:
 *  - `NAMESPACE_INACCESSIBLE` -> 403 (the caller cannot see the namespace);
 *  - `WORKSTREAM_UNRESOLVABLE` -> 404;
 *  - every other unmet precondition -> 422 (well-formed but not runnable).
 */
class WorkspacePrecheckException(
    code: String,
    message: String,
    details: Any? = null,
    cause: Throwable? = null,
) : FactoryException(statusFor(code), code, message, details, cause) {

    companion object {
        private fun statusFor(code: String): Int = when (code) {
            WorkspacePrecheckCodes.NAMESPACE_INACCESSIBLE -> 403
            WorkspacePrecheckCodes.WORKSTREAM_UNRESOLVABLE -> 404
            else -> 422
        }
    }
}
