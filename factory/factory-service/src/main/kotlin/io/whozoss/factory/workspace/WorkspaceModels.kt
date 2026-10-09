package io.whozoss.factory.workspace

import io.whozoss.factory.persistence.TenantScope

/**
 * Read-only view of the AgentOS Git association of a namespace, as returned by
 * `GET /api/namespaces/{namespaceId}/git` (`NamespaceGitResource`).
 *
 * The endpoint is the ONLY AgentOS surface exposing a namespace's Git readiness.
 * [gitAvailable] is false when the endpoint is unavailable (the GIT plugin is
 * not loaded / AgentOS answers 404), which is precisely "Git workspaces are not
 * enabled on this instance".
 */
data class NamespaceGitState(
    val gitAvailable: Boolean,
    val associated: Boolean,
    /** `PREPARING`, `READY` or `FAILED`; null while no clone has been attempted. */
    val checkoutStatus: String? = null,
    val failureReason: String? = null,
)

/** A snapshot of a run's workspace status, plus the worktree path once it is known. */
data class WorkspaceSnapshot(
    val status: WorkspaceStatus,
    val worktreePath: String? = null,
    val repoRoot: String? = null,
    val caseId: String? = null,
    val failureReason: String? = null,
)

/** Facts returned once a run's workspace is proven usable. */
data class WorkspaceReadyFacts(
    val worktreePath: String,
    val repoRoot: String? = null,
    val caseId: String? = null,
)

/** Outcome of a successful read-only pre-check. */
data class WorkspacePrecheckReport(
    val enabled: Boolean,
    val namespaceId: String,
    /** Checks that were actually verified against an available read API. */
    val verified: List<String>,
    /** Checks that could not be verified because the API does not expose them. */
    val unverified: List<String>,
)

/**
 * Read-only API surface consulted by the workspace pre-check.
 *
 * Every method is a pure query: it never mutates namespace, Git or agent
 * configuration. An unavailable API is represented explicitly (`null`), never
 * silently assumed to be satisfied.
 *
 * Localisation of the underlying AgentOS APIs:
 *  - namespace accessibility -> `GET /api/namespaces/{namespaceId}`
 *  - Git association/checkout -> `GET /api/namespaces/{namespaceId}/git`
 *  - available agents -> `GET /api/agent-configs/by-parentId/{namespaceId}`
 */
interface WorkspacePrecheckPort {

    /** Whether the namespace exists and is visible to [callerExternalUserId]. */
    fun isNamespaceAccessible(namespaceId: String, callerExternalUserId: String?): Boolean

    /**
     * The namespace Git association, or `null` when neither a Git plugin nor an
     * association is exposed (the endpoint answers 404).
     */
    fun namespaceGitState(namespaceId: String, callerExternalUserId: String?): NamespaceGitState?

    /**
     * The names of the agents published for the namespace, or `null` when the
     * read API did not return a parseable list (the check is then reported as
     * unverified rather than silently passed).
     */
    fun availableAgents(namespaceId: String, callerExternalUserId: String?): List<String>?
}

/**
 * Read-only resolution of the run's workstream context (the tenant scope's
 * workstream must exist and be resolvable before a run is provisioned).
 */
fun interface WorkspaceContextPort {
    fun isWorkstreamResolvable(scope: TenantScope): Boolean
}

/**
 * Read-only source of a run's workspace status.
 *
 * The Factory-owned [io.whozoss.factory.environment.domain.WorkEnvironment] is
 * authoritative for the run's worktree path: AgentOS' `CaseResourceBinding`
 * carries neither the path nor a read endpoint, so it cannot be polled by the
 * Factory (documented gap — see [WorkspaceStateWaiter]).
 */
interface WorkspaceStatePort {

    /** The latest workspace snapshot of [workflowId], or `null` when none was requested. */
    fun latestForWorkflow(scope: TenantScope, workflowId: String): WorkspaceSnapshot?
}
