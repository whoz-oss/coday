package io.whozoss.agentos.sdk.caseFlow

/**
 * Status of a case during its lifecycle.
 *
 * Normal flow (no gate):
 *   CREATED → RUNNING → IDLE → RUNNING → IDLE → ... → KILLED
 *
 * Gated flow (Git workspace required):
 *   CREATED → PENDING → RUNNING → IDLE → ... → KILLED
 *
 * - [CREATED]  Case just created; no agent turn has started yet.
 * - [PENDING]  A turn is queued and waiting for the launch gate to admit it.
 * - [RUNNING]  Agent turn in progress.
 * - [IDLE]     Agent turn complete; runtime alive, SSE open, waiting for next user message.
 * - [KILLED]   Permanently destroyed by an explicit kill request. Terminal.
 * - [ERROR]    Terminated due to an unrecoverable error. Terminal.
 */
enum class CaseStatus {
    /**
     * A case has just been created and has not yet received its first agent turn.
     * This is the initial status assigned to every new case.
     */
    CREATED,

    /**
     * A turn is queued and waiting for the [io.whozoss.agentos.caseFlow.CaseLaunchGate]
     * to admit it (e.g. while a Git workspace is being prepared). Distinct from [CREATED]:
     * the case already has a user message and an agent turn in flight; it is merely
     * held back until its workspace is ready.
     */
    PENDING,

    RUNNING,
    IDLE,
    KILLED,
    ERROR;

    /** Returns true if the case has reached a final state and will no longer produce events. */
    fun isTerminal(): Boolean = this == KILLED || this == ERROR
}
