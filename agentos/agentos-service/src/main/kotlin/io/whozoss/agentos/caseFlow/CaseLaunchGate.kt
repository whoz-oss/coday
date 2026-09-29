package io.whozoss.agentos.caseFlow

import java.util.UUID

/**
 * Decides whether an agent run may start for a case right now.
 *
 * Installed only by an optional capability (today, Git workspaces with
 * `agentos.git.workspaces.enabled`). Without one, [CaseServiceImpl] starts every run
 * immediately, as it always did.
 *
 * A gate that says no is **not** an error: the case stays `PENDING` with its message persisted,
 * and the capability calls [CaseService.resumeIfPending] once the obstacle clears.
 */
interface CaseLaunchGate {
    /**
     * Whether a run may be launched for [caseId]. An error must never permit execution in an
     * unrelated directory: throw rather than answer.
     */
    fun canLaunch(caseId: UUID): Boolean

    /**
     * Coordinate a short admission attempt with the capability's resource lifecycle.
     *
     * Run [action] immediately when admission can be inspected safely. When a resource operation
     * prevents it, return without blocking and call [onAvailable] after that operation finishes.
     */
    fun withAdmission(
        caseId: UUID,
        onAvailable: () -> Unit,
        action: () -> Unit,
    ) = action()

    /** Reject input when the capability can no longer accept work on this case. */
    fun requireAccepting(caseId: UUID) = Unit

    /** Keep a non-terminal case open for fresh input after its resource survives a server shutdown. */
    fun keepOpenOnShutdown(caseId: UUID): Boolean = false
}
