package io.whozoss.agentos.caseFlow

import java.util.UUID

/**
 * Decides whether an agent run may start for a case right now.
 *
 * Optional capabilities implement this contract without exposing their resource model or
 * coordination mechanism to the case layer.
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
     * An error must never permit execution in an unrelated directory: throw rather than answer.
     * The case service retries a failed decision a few times, then reports it to the user.
     */
    fun canLaunch(caseId: UUID): Boolean

    /**
     * Coordinate a short admission attempt with the capability's resource lifecycle.
     *
     * Run [action] immediately when admission can be inspected safely. When a resource operation
     * prevents it, return without blocking and call [onAvailable] after that operation finishes.
     * The callback schedules a fresh attempt; it must not bypass [canLaunch].
     */
    fun withAdmission(caseId: UUID, onAvailable: () -> Unit, action: () -> Unit) = action()

    /** Reject input when the capability can no longer accept work on this case. */
    fun requireAccepting(caseId: UUID) = Unit

    /** Keep a non-terminal case available for fresh input after its resource survives server shutdown. */
    fun keepOpenOnShutdown(caseId: UUID): Boolean = false

    companion object {
        /** Used when no capability gates runs, which is every deployment without Git workspaces. */
        val ALWAYS: CaseLaunchGate =
            object : CaseLaunchGate {
                override fun canLaunch(caseId: UUID): Boolean = true
            }
    }
}
