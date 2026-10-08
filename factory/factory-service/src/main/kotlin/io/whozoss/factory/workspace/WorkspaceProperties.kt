package io.whozoss.factory.workspace

import org.springframework.boot.context.properties.ConfigurationProperties

/**
 * Strongly-typed binding of the `factory.workspace.*` tree: the Factory
 * control-plane policy of the run workspace pre-check and availability waiter.
 *
 * ## Availability of each prerequisite through the consumed APIs
 *
 * | prerequisite                | source                                                    | available |
 * |-----------------------------|-----------------------------------------------------------|-----------|
 * | namespace accessibility     | `GET /api/namespaces/{id}` (proxy `fetchNamespace`)       | yes       |
 * | Git workspaces enabled      | `GET /api/namespaces/{id}/git` 404 / this kill-switch      | yes       |
 * | Git configuration adapted   | `GET /api/namespaces/{id}/git` (`associated`, checkout)    | yes       |
 * | required agents available   | `GET /api/agent-configs/by-parentId/{id}`                  | yes       |
 * | auto worktree for root cases| **no AgentOS read API** — this Factory policy is the source | documented |
 *
 * AgentOS exposes **no** read endpoint for `autoWorktreeForRootCases`, nor for a
 * case's `CaseResourceBinding` status/worktree path (the binding is only
 * persisted, never served). Those values are therefore NOT invented: the
 * pre-check consults this Factory policy for the auto-worktree flag and records
 * every unavailable check as *unverified* in the report instead of assuming it is
 * satisfied.
 */
@ConfigurationProperties(prefix = "factory.workspace")
data class WorkspaceProperties(
    /**
     * Master switch of the Lot F orchestration. When `false`, the pre-check and
     * the workspace waiter are disabled (every run keeps the caller-provided
     * `repoRoot`), which is the explicit configuration used by the shared
     * integration-test profile. Production leaves it `true`.
     */
    val enabled: Boolean = true,
    /**
     * Factory mirror of `agentos.git.workspaces.enabled`. When `false` the run is
     * refused with [WorkspacePrecheckCodes.GIT_WORKSPACES_DISABLED]: no worktree
     * can be provisioned.
     */
    val gitWorkspacesEnabled: Boolean = true,
    /**
     * Whether a root case automatically receives an isolated Git worktree.
     * When `false` the run is refused with
     * [WorkspacePrecheckCodes.AUTO_WORKTREE_DISABLED].
     */
    val autoWorktreeForRootCases: Boolean = true,
    /** Wall-clock budget of the workspace-ready wait, in milliseconds. */
    val readyTimeoutMs: Long = 120_000L,
    /** Interval between two workspace status reads, in milliseconds. */
    val readyPollIntervalMs: Long = 1_000L,
)
