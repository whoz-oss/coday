package io.whozoss.factory.environment.port

import io.whozoss.factory.environment.domain.WorkEnvironment

/** Path-free immutable snapshot returned by the environment owner. */
data class EnvironmentGitInspection(
    val providerId: String,
    val environmentId: String,
    val environmentRevision: Int,
    val head: String,
    val currentBranch: String?,
    val baselineHash: String,
    val baselinePathCount: Int,
    val baselinePaths: List<String>,
    val dirty: Boolean,
    /** Opaque provider snapshot token; callers must only relay it. */
    val inspectionToken: String,
    val observedAt: String? = null,
)

sealed interface EnvironmentGitInspectionResult {
    data class Inspected(val inspection: EnvironmentGitInspection) : EnvironmentGitInspectionResult
    data class Refused(val facts: EnvironmentGitFacts) : EnvironmentGitInspectionResult
}

/** Path-free command carrying only trusted preconditions obtained from inspect. */
data class PrepareEnvironmentBranchCommand(
    val environmentRef: String,
    val expectedEnvironmentRevision: Int,
    val expectedHead: String,
    val expectedBaselineHash: String,
    val inspectionToken: String,
    val branchName: String,
)

data class EnvironmentGitFacts(
    val providerId: String,
    val environmentId: String,
    val environmentRevision: Int,
    val branch: String?,
    val previousBranch: String?,
    val startingHead: String?,
    val baselineHash: String?,
    val baselinePathCount: Int? = null,
    val baselinePaths: List<String> = emptyList(),
    val dirty: Boolean? = null,
    val mutationApplied: Boolean,
    val failureStage: String,
    val message: String? = null,
)

sealed interface PrepareEnvironmentBranchResult {
    data class Prepared(val facts: EnvironmentGitFacts) : PrepareEnvironmentBranchResult
    data class Refused(val facts: EnvironmentGitFacts) : PrepareEnvironmentBranchResult
}

/** Provider owning inspection, Git mutation, path knowledge and environment-level serialization. */
interface EnvironmentGitProvider {
    val providerId: String
    fun supports(environment: WorkEnvironment): Boolean
    fun inspect(environment: WorkEnvironment, expectedEnvironmentRevision: Int): EnvironmentGitInspectionResult
    fun prepareBranch(environment: WorkEnvironment, command: PrepareEnvironmentBranchCommand): PrepareEnvironmentBranchResult
}
