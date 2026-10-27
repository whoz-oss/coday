package io.whozoss.factory.workspace

import io.whozoss.factory.environment.domain.WorkEnvironmentState
import io.whozoss.factory.environment.persistence.WorkEnvironmentRepository
import io.whozoss.factory.persistence.TenantScope
import org.springframework.stereotype.Component

/**
 * Read-only [WorkspaceStatePort] backed by the Factory-owned
 * `work_environments` aggregate.
 *
 * The Factory persists the run's `worktreePath` on its
 * [io.whozoss.factory.environment.domain.WorkEnvironment]; this is the
 * authoritative, available source of the run workspace. AgentOS'
 * `CaseResourceBinding` persists a comparable status but exposes no read
 * endpoint, so it cannot be consumed here.
 *
 * Mapping:
 *  - `provisioning` -> [WorkspaceStatus.PREPARING];
 *  - `ready` / `busy` -> [WorkspaceStatus.READY];
 *  - `decommissioned` -> [WorkspaceStatus.FAILED];
 *  - no environment -> `null` (no workspace was requested for this run).
 */
@Component
class WorkEnvironmentWorkspaceStateAdapter(
    private val repository: WorkEnvironmentRepository,
) : WorkspaceStatePort {

    override fun latestForWorkflow(scope: TenantScope, workflowId: String): WorkspaceSnapshot? {
        val environment = repository.findLatestByWorkflowId(scope, workflowId) ?: return null
        return when (environment.lifecycleState) {
            WorkEnvironmentState.PROVISIONING -> WorkspaceSnapshot(
                status = WorkspaceStatus.PREPARING,
                caseId = environment.parentCaseId,
            )
            WorkEnvironmentState.READY,
            WorkEnvironmentState.BUSY,
            -> WorkspaceSnapshot(
                status = WorkspaceStatus.READY,
                worktreePath = environment.worktreePath.takeIf { it.isNotBlank() },
                repoRoot = environment.repoRoot.takeIf { it.isNotBlank() },
                caseId = environment.parentCaseId,
            )
            WorkEnvironmentState.DECOMMISSIONED -> WorkspaceSnapshot(
                status = WorkspaceStatus.FAILED,
                caseId = environment.parentCaseId,
                failureReason = "The run workspace was decommissioned before any code step could run.",
            )
        }
    }
}
