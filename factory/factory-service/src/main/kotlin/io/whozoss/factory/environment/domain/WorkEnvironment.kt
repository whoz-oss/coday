package io.whozoss.factory.environment.domain

import java.time.Instant

/**
 * Aggregate root of a work-unit environment (a provisioned Git worktree).
 *
 * The durable surface is the V6 `work_environments` table: the lifecycle state
 * maps onto its `status` column, the optimistic-locking [revision] onto its
 * `revision` column, and the whole descriptor is kept verbatim in the JSONB
 * `payload` — mirroring
 * `factory/src/adapters/persistence/sql/sql-work-environment-repository.ts`.
 *
 * The environment is identified by `(organizationId, workstreamId,
 * environmentId)` and is additionally addressable by its `workflowId`.
 */
data class WorkEnvironment(
    val organizationId: String,
    val workstreamId: String,
    val environmentId: String,
    val workUnitId: String,
    val workflowId: String,
    val namespaceId: String,
    val parentCaseId: String? = null,
    val repoRoot: String,
    val integrationBranch: String,
    val branch: String,
    val worktreePath: String,
    val baseCommit: String? = null,
    val lifecycleState: WorkEnvironmentState = WorkEnvironmentState.PROVISIONING,
    val createdBy: String,
    val createdAt: Instant = Instant.now(),
    val revision: Int = 1,
    val envType: String = DEFAULT_ENV_TYPE,
) {
    companion object {
        const val DEFAULT_ENV_TYPE = "work-unit-environment"
    }
}
