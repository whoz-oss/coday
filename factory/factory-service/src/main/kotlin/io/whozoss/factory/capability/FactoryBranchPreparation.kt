package io.whozoss.factory.capability

import io.whozoss.factory.environment.persistence.WorkEnvironmentRepository
import io.whozoss.factory.environment.port.EnvironmentGitProvider
import io.whozoss.factory.environment.port.EnvironmentGitFacts
import io.whozoss.factory.environment.port.EnvironmentGitInspectionResult
import io.whozoss.factory.environment.port.PrepareEnvironmentBranchCommand
import io.whozoss.factory.environment.port.PrepareEnvironmentBranchResult
import io.whozoss.factory.persistence.TenantScope
import org.springframework.stereotype.Component

const val PREPARE_WORK_BRANCH = "prepare-work-branch"
const val BRANCH_PREPARATION_FAILED = "BRANCH_PREPARATION_FAILED"

sealed interface BranchPreparationResult {
    data class Prepared(val facts: EnvironmentGitFacts) : BranchPreparationResult
    data class Refused(val facts: EnvironmentGitFacts) : BranchPreparationResult
}

/** Path-free Factory control plane: resolves durable binding and delegates mutation to its owner provider. */
@Component
class FactoryBranchPreparation(
    private val environments: WorkEnvironmentRepository,
    providers: List<EnvironmentGitProvider>,
) {
    private val providers = providers.toList()

    fun prepare(
        scope: TenantScope,
        workflowId: String,
        branchName: String,
    ): BranchPreparationResult {
        val environment = environments.findLatestByWorkflowId(scope, workflowId)
            ?: return refused("binding_missing", "No WorkUnitEnvironment is bound to this workflow.", workflowId, branchName)
        if (environment.workflowId != workflowId) return refused("binding_mismatch", "Environment ownership mismatch.", environment.environmentId, branchName)
        val provider = providers.singleOrNull { it.supports(environment) }
            ?: return refused("provider_unsupported", "No provider supports the bound environment.", environment.environmentId, branchName, environment.revision)
        val inspection = when (val result = provider.inspect(environment, environment.revision)) {
            is EnvironmentGitInspectionResult.Inspected -> result.inspection
            is EnvironmentGitInspectionResult.Refused -> return BranchPreparationResult.Refused(result.facts)
        }
        val command = PrepareEnvironmentBranchCommand(
            environment.environmentId, environment.revision, inspection.head, inspection.baselineHash,
            inspection.inspectionToken, branchName,
        )
        return when (val result = provider.prepareBranch(environment, command)) {
                is PrepareEnvironmentBranchResult.Prepared -> BranchPreparationResult.Prepared(result.facts)
            is PrepareEnvironmentBranchResult.Refused -> BranchPreparationResult.Refused(result.facts)
        }
    }

    private fun refused(stage: String, message: String, environmentId: String, branch: String, revision: Int = 0) =
        BranchPreparationResult.Refused(EnvironmentGitFacts("unsupported", environmentId, revision, branch, null, null, null,
            mutationApplied = false, failureStage = stage, message = message))

}
