package io.whozoss.factory.environment.service

import io.whozoss.factory.environment.domain.EnvironmentIdentityConflictException
import io.whozoss.factory.environment.domain.EnvironmentNotFoundException
import io.whozoss.factory.environment.domain.InvalidEnvironmentStateException
import io.whozoss.factory.environment.domain.WorkEnvironment
import io.whozoss.factory.environment.domain.WorkEnvironmentState
import io.whozoss.factory.environment.persistence.WorkEnvironmentRepository
import io.whozoss.factory.environment.port.GitProvisionRequest
import io.whozoss.factory.environment.port.GitProvisioner
import io.whozoss.factory.environment.port.GitReconciliation
import io.whozoss.factory.persistence.TenantScope
import org.springframework.stereotype.Service
import org.springframework.transaction.annotation.Transactional
import java.time.Instant

/** Command accepted by [WorkUnitEnvironmentService.provision]. */
data class ProvisionEnvironmentCommand(
    val workflowId: String,
    val workUnitId: String,
    val namespaceId: String,
    val parentCaseId: String? = null,
    val integrationBranch: String,
    val branch: String,
    val createdBy: String,
    val repoRoot: String? = null,
    val environmentId: String? = null,
)

/** Result of a provisioning attempt: whether a new environment was written. */
data class ProvisionOutcome(
    val changed: Boolean,
    val environment: WorkEnvironment,
)

/** An environment plus its current reconciliation against the Git worktree. */
data class EnvironmentInspection(
    val environment: WorkEnvironment,
    val reconciliation: GitReconciliation?,
    val headCommit: String?,
)

/**
 * Application service of the WORK ENVIRONMENTS aggregate.
 *
 * Owns the lifecycle state machine (`provisioning` -> `ready` <-> `busy` ->
 * `decommissioned`) and delegates every Git worktree side effect to the injected
 * [GitProvisioner]. Port of `WorkUnitEnvironmentService` in
 * `factory/src/application/environment/work-unit-environment-service.ts`,
 * restricted to the durable `work_environments` projection.
 */
@Service
class WorkUnitEnvironmentService(
    private val repository: WorkEnvironmentRepository,
    private val provisioner: GitProvisioner,
) {

    /**
     * Provision (or idempotently reuse) the environment of a workflow/work-unit
     * pair. A fresh environment is first persisted in `provisioning`, then
     * advanced to `ready` once the [GitProvisioner] has materialised the
     * worktree.
     */
    @Transactional
    fun provision(
        scope: TenantScope,
        command: ProvisionEnvironmentCommand,
        now: Instant = Instant.now(),
    ): ProvisionOutcome {
        val environmentId = command.environmentId ?: "${command.workflowId}-${command.workUnitId}"
        val existing = repository.findByEnvironmentId(scope, environmentId)
        if (existing != null) {
            assertSameIdentity(existing, command, environmentId)
            if (existing.lifecycleState == WorkEnvironmentState.DECOMMISSIONED) {
                throw InvalidEnvironmentStateException(
                    "Environment '$environmentId' is decommissioned and cannot be re-provisioned",
                    details = mapOf("environmentId" to environmentId),
                )
            }
            if (existing.lifecycleState != WorkEnvironmentState.PROVISIONING) {
                return ProvisionOutcome(changed = false, environment = existing)
            }
            return advanceToReady(scope, existing, command, now)
        }

        val draft = WorkEnvironment(
            organizationId = scope.organizationId,
            workstreamId = scope.workstreamId,
            environmentId = environmentId,
            workUnitId = command.workUnitId,
            workflowId = command.workflowId,
            namespaceId = command.namespaceId,
            parentCaseId = command.parentCaseId,
            repoRoot = command.repoRoot ?: DEFAULT_REPO_ROOT,
            integrationBranch = command.integrationBranch,
            branch = command.branch,
            worktreePath = "",
            baseCommit = null,
            lifecycleState = WorkEnvironmentState.PROVISIONING,
            createdBy = command.createdBy,
            createdAt = now,
            revision = 1,
        )
        val reserved = repository.insert(scope, draft)
        return advanceToReady(scope, reserved, command, now)
    }

    private fun advanceToReady(
        scope: TenantScope,
        environment: WorkEnvironment,
        command: ProvisionEnvironmentCommand,
        now: Instant,
    ): ProvisionOutcome {
        val facts = provisioner.provisionWorktree(
            GitProvisionRequest(
                environmentId = environment.environmentId,
                workUnitId = environment.workUnitId,
                workflowId = environment.workflowId,
                namespaceId = environment.namespaceId,
                integrationBranch = environment.integrationBranch,
                branch = environment.branch,
            ),
        )
        val ready = environment.copy(
            repoRoot = facts.repoRoot,
            worktreePath = facts.worktreePath,
            baseCommit = facts.baseCommit,
            lifecycleState = WorkEnvironmentState.READY,
        )
        val saved = repository.updateState(scope, ready, environment.revision, now)
        return ProvisionOutcome(changed = true, environment = saved)
    }

    /** Reconcile the environment of [workflowId] against its Git worktree. */
    @Transactional(readOnly = true)
    fun inspect(scope: TenantScope, workflowId: String): EnvironmentInspection {
        val environment = activeEnvironment(scope, workflowId)
        val reconciliation = provisioner.reconcile(environment)
        return EnvironmentInspection(
            environment = environment,
            reconciliation = reconciliation,
            headCommit = (reconciliation as? GitReconciliation.Owned)?.headCommit,
        )
    }

    /** Mark a `ready` environment as `busy` (it is now bound to an active work unit). */
    @Transactional
    fun markBusy(scope: TenantScope, workflowId: String, now: Instant = Instant.now()): WorkEnvironment {
        val environment = activeEnvironment(scope, workflowId)
        if (environment.lifecycleState == WorkEnvironmentState.BUSY) return environment
        if (!environment.lifecycleState.canTransitionTo(WorkEnvironmentState.BUSY)) {
            throw InvalidEnvironmentStateException(
                "Cannot mark environment '${environment.environmentId}' busy from " +
                    "'${environment.lifecycleState.dbValue}'",
                details = mapOf("lifecycleState" to environment.lifecycleState.dbValue),
            )
        }
        return repository.updateState(
            scope,
            environment.copy(lifecycleState = WorkEnvironmentState.BUSY),
            environment.revision,
            now,
        )
    }

    /** Mark a `busy` environment back as `ready`. */
    @Transactional
    fun markReady(scope: TenantScope, workflowId: String, now: Instant = Instant.now()): WorkEnvironment {
        val environment = activeEnvironment(scope, workflowId)
        if (environment.lifecycleState == WorkEnvironmentState.READY) return environment
        if (!environment.lifecycleState.canTransitionTo(WorkEnvironmentState.READY)) {
            throw InvalidEnvironmentStateException(
                "Cannot mark environment '${environment.environmentId}' ready from " +
                    "'${environment.lifecycleState.dbValue}'",
                details = mapOf("lifecycleState" to environment.lifecycleState.dbValue),
            )
        }
        return repository.updateState(
            scope,
            environment.copy(lifecycleState = WorkEnvironmentState.READY),
            environment.revision,
            now,
        )
    }

    /**
     * Release (decommission) the environment of [workflowId]. Idempotent: an
     * already-decommissioned environment is returned unchanged and a missing one
     * fails with `ENVIRONMENT_NOT_FOUND`.
     */
    @Transactional
    fun release(
        scope: TenantScope,
        workflowId: String,
        target: WorkEnvironmentState = WorkEnvironmentState.DECOMMISSIONED,
        now: Instant = Instant.now(),
    ): EnvironmentInspection {
        if (target != WorkEnvironmentState.DECOMMISSIONED) {
            throw InvalidEnvironmentStateException("Only 'decommissioned' is a supported release target")
        }
        val environment = activeEnvironment(scope, workflowId)
        if (environment.lifecycleState == WorkEnvironmentState.DECOMMISSIONED) {
            return EnvironmentInspection(environment, GitReconciliation.Absent, null)
        }
        if (!environment.lifecycleState.canTransitionTo(target)) {
            throw InvalidEnvironmentStateException(
                "Cannot release environment '${environment.environmentId}' from " +
                    "'${environment.lifecycleState.dbValue}'",
                details = mapOf("lifecycleState" to environment.lifecycleState.dbValue),
            )
        }
        val released = repository.updateState(
            scope,
            environment.copy(lifecycleState = target),
            environment.revision,
            now,
        )
        provisioner.removeWorktree(released)
        return EnvironmentInspection(released, GitReconciliation.Absent, null)
    }

    private fun activeEnvironment(scope: TenantScope, workflowId: String): WorkEnvironment =
        repository.findLatestByWorkflowId(scope, workflowId)
            ?: throw EnvironmentNotFoundException("No environment found for workflow '$workflowId'")

    private fun assertSameIdentity(
        existing: WorkEnvironment,
        command: ProvisionEnvironmentCommand,
        environmentId: String,
    ) {
        val same = existing.workUnitId == command.workUnitId &&
            existing.workflowId == command.workflowId &&
            existing.namespaceId == command.namespaceId &&
            existing.integrationBranch == command.integrationBranch &&
            existing.branch == command.branch
        if (!same) {
            throw EnvironmentIdentityConflictException(
                "Environment '$environmentId' already exists with a different identity",
                details = mapOf("environmentId" to environmentId),
            )
        }
    }

    private companion object {
        const val DEFAULT_REPO_ROOT = "repo"
    }
}
