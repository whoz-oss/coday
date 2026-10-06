package io.whozoss.factory.environment.port

import io.whozoss.factory.environment.domain.WorkEnvironment

/** What the Git provisioner reports back once a worktree is ready for a request. */
data class GitProvisionFacts(
    val repoRoot: String,
    val worktreePath: String,
    val baseCommit: String?,
    val headCommit: String,
)

/** Input accepted by [GitProvisioner.provisionWorktree]. */
data class GitProvisionRequest(
    val environmentId: String,
    val workUnitId: String,
    val workflowId: String,
    val namespaceId: String,
    val integrationBranch: String,
    val branch: String,
)

/** Result of reconciling the recorded intent against the actual Git worktree. */
sealed interface GitReconciliation {
    /** The worktree is owned by this environment: [headCommit]/[baseCommit] are the observed values. */
    data class Owned(val headCommit: String?, val baseCommit: String?) : GitReconciliation

    /** The worktree exists but its ownership cannot be proven. */
    data object Uncertain : GitReconciliation

    /** No worktree exists for the environment. */
    data object Absent : GitReconciliation
}

/**
 * Git worktree provisioner port.
 *
 * Worktree creation/teardown is kept behind this interface so the environment
 * application service never shells out to Git directly: the same orchestration
 * runs against the in-memory [FakeGitProvisioner] (default) or a real
 * implementation. Port of `WorkUnitEnvironmentGit` in
 * `factory/src/application/environment/work-unit-environment-service.ts`.
 */
interface GitProvisioner {

    /** Create (or adopt) the worktree for [request] and report its observed facts. */
    fun provisionWorktree(request: GitProvisionRequest): GitProvisionFacts

    /** Reconcile the recorded environment intent against the actual worktree. */
    fun reconcile(environment: WorkEnvironment): GitReconciliation

    /** Remove the worktree backing [environment]; a no-op when it is already absent. */
    fun removeWorktree(environment: WorkEnvironment)
}
