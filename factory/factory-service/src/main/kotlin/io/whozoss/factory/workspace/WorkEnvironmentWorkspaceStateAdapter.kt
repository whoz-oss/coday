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
 * authoritative source consumed here. Parent-run and standalone provisioning adopt
 * AgentOS' root workspace/changes path into it; this adapter does not discover paths.
 *
 * Mapping:
 *  - `provisioning` -> [WorkspaceStatus.PREPARING];
 *  - `ready` / `busy` -> [WorkspaceStatus.READY];
 *  - `decommissioned` -> [WorkspaceStatus.FAILED];
 *  - no environment -> null (absence, never code-step fallback authorization).
 */
@Component
class WorkEnvironmentWorkspaceStateAdapter(
    private val repository: WorkEnvironmentRepository,
) : WorkspaceStatePort {

    override fun latestForWorkflow(scope: TenantScope, workflowId: String): WorkspaceSnapshot? {
        val environment = repository.findLatestByWorkflowId(scope, workflowId) ?: return null
        environment.provisioningFailure?.let { failure ->
            return WorkspaceSnapshot(status = WorkspaceStatus.FAILED, caseId = environment.parentCaseId, failureReason = failure)
        }
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
                environmentId = environment.environmentId,
                baseCommit = environment.baseCommit,
                namespaceId = environment.namespaceId,
            )
            WorkEnvironmentState.DECOMMISSIONED -> WorkspaceSnapshot(
                status = WorkspaceStatus.FAILED,
                caseId = environment.parentCaseId,
                failureReason = "The run workspace was decommissioned before any code step could run.",
            )
        }
    }
}
