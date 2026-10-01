package io.whozoss.agentos.caseFlow

import java.util.UUID

/**
 * The outcome of a launch-gate check: whether a run may start, must wait, or is permanently refused.
 *
 * Replaces the previous `canLaunch(): Boolean` which collapsed [Wait] and [Refuse] into a single
 * `false`, causing `FAILED`-binding cases to stay `PENDING` indefinitely with no warning emitted.
 */
sealed interface LaunchDecision {
    /** The run may start immediately. */
    data object Admit : LaunchDecision

    /**
     * The run must wait: the resource is temporarily unavailable (e.g. workspace still preparing).
     * The case stays `PENDING`; the gate will call [CaseService.resumeIfPending] once ready.
     */
    data class Wait(val reason: String) : LaunchDecision

    /**
     * The run is permanently refused: the resource is in a terminal-failure state
     * (e.g. `FAILED`, `DELETING`, `REMOVED`). A [io.whozoss.agentos.sdk.caseEvent.WarnEvent]
     * is emitted and the case returns to `IDLE`.
     */
    data class Refuse(val reason: String) : LaunchDecision
}

/**
 * Decides whether an agent run may start for a case right now.
 *
 * Installed only by an optional capability (today, Git workspaces with
 * `agentos.git.workspaces.enabled`). Without one, [CaseServiceImpl] starts every run
 * immediately, as it always did.
 *
 * A gate that says [LaunchDecision.Wait] is **not** an error: the case stays `PENDING` with its
 * message persisted, and the capability calls [CaseService.resumeIfPending] once the obstacle
 * clears. A gate that says [LaunchDecision.Refuse] emits a warning and returns the case to `IDLE`.
 */
interface CaseLaunchGate {
    /**
     * Decide whether a run may be launched for [caseId].
     *
     * - [LaunchDecision.Admit]  — start the run now.
     * - [LaunchDecision.Wait]   — hold the turn; the gate will resume it when ready.
     * - [LaunchDecision.Refuse] — emit a warning and return to IDLE; do not retry.
     *
     * An error must never permit execution in an unrelated directory: throw rather than return
     * [LaunchDecision.Admit] when the workspace cannot be determined.
     */
    fun launchDecision(caseId: UUID): LaunchDecision

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
