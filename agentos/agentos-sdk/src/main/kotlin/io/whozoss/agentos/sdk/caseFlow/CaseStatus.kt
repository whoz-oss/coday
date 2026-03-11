package io.whozoss.agentos.sdk.caseFlow

/**
 * Status of a case during its lifecycle.
 *
 * Normal flow:
 *   PENDING → RUNNING → IDLE → RUNNING → IDLE → ... → KILLED
 *
 * - [PENDING]  Created, not yet started.
 * - [RUNNING]  Agent turn in progress.
 * - [IDLE]     Agent turn complete; runtime alive, SSE open, waiting for next user message.
 * - [KILLED]   Permanently destroyed by an explicit kill request. Terminal.
 * - [ERROR]    Terminated due to an unrecoverable error. Terminal.
 */
enum class CaseStatus {
    PENDING,
    RUNNING,
    IDLE,
    KILLED,
    ERROR;

    /** Returns true if the case has reached a final state and will no longer produce events. */
    fun isTerminal(): Boolean = this == KILLED || this == ERROR
}
