package io.whozoss.factory.workspace

/**
 * Lifecycle of the workspace (Git worktree) allocated to a Factory run.
 *
 * This is the *Factory* projection of AgentOS' `CaseResourceStatus`
 * (`REQUESTED`, `PREPARING`, `READY`, `FAILED`, `DELETING`, `REMOVED`) collapsed
 * onto the three states the run orchestration needs:
 *
 *  - [PREPARING] — the workspace has been requested but is not usable yet
 *    (`REQUESTED`/`PREPARING`): the run must wait, never run a build against it.
 *  - [READY] — the worktree exists and tools may run in it.
 *  - [FAILED] — preparation failed, cleanup started or the worktree is gone
 *    (`FAILED`/`DELETING`/`REMOVED`): retryable but never usable as-is.
 */
enum class WorkspaceStatus {
    PREPARING,
    READY,
    FAILED,
    ;

    companion object {
        /**
         * Collapses an AgentOS `CaseResourceStatus` wire value onto this enum.
         * An unknown/absent value is treated as [PREPARING] (fail-closed: never
         * assume a workspace is usable without proof).
         */
        fun fromAgentOsStatus(raw: String?): WorkspaceStatus = when (raw?.uppercase()) {
            "READY" -> READY
            "FAILED", "DELETING", "REMOVED" -> FAILED
            else -> PREPARING
        }
    }
}
