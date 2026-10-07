package io.whozoss.factory.environment.web

import io.whozoss.factory.environment.domain.WorkEnvironment
import io.whozoss.factory.environment.domain.WorkEnvironmentState
import io.whozoss.factory.environment.service.EnvironmentInspection
import io.whozoss.factory.environment.port.GitReconciliation

/** Canonical HTTP success envelope: `{ "data": ... }`. */
data class EnvironmentDataEnvelope<T>(
    val data: T,
)

/** Request body of `POST .../environment/provision`. */
data class ProvisionEnvironmentRequest(
    val workUnitId: String? = null,
    val integrationBranch: String? = null,
    val branch: String? = null,
    val namespaceId: String? = null,
    val parentCaseId: String? = null,
    val repoRoot: String? = null,
)

/** Request body of `POST .../environment/release`. */
data class ReleaseEnvironmentRequest(
    val state: String? = null,
)

/** Reconciliation projection returned to callers. */
data class ReconciliationResponse(
    val status: String,
    val headCommit: String? = null,
    val baseCommit: String? = null,
)

/** File-access projection returned to callers. */
data class FileAccessResponse(
    val status: String,
    val code: String?,
    val rootPath: String,
)

/**
 * Public projection of an environment snapshot.
 *
 * Mirrors the Node `PublicEnvironment` shape in
 * `factory/src/application/environment/work-unit-environment-controller.ts`:
 * the descriptor, its current Git reconciliation, the observed head commit and
 * whether the worktree is bound for file access.
 */
data class EnvironmentResponse(
    val revision: Int,
    val environment: WorkEnvironment,
    val reconciliation: ReconciliationResponse?,
    val headCommit: String?,
    val fileAccess: FileAccessResponse,
)

/** Map an [EnvironmentInspection] onto the public HTTP projection. */
fun EnvironmentInspection.toResponse(): EnvironmentResponse {
    val bound = environment.lifecycleState == WorkEnvironmentState.READY ||
        environment.lifecycleState == WorkEnvironmentState.BUSY
    return EnvironmentResponse(
        revision = environment.revision,
        environment = environment,
        reconciliation = reconciliation?.toResponse(),
        headCommit = headCommit,
        fileAccess = FileAccessResponse(
            status = if (bound) "bound" else "blocked",
            code = if (bound) null else "ENVIRONMENT_NOT_BOUND",
            rootPath = environment.worktreePath,
        ),
    )
}

/** Map a [GitReconciliation] onto the public HTTP projection. */
fun GitReconciliation.toResponse(): ReconciliationResponse = when (this) {
    is GitReconciliation.Owned -> ReconciliationResponse(
        status = "owned",
        headCommit = headCommit,
        baseCommit = baseCommit,
    )

    GitReconciliation.Uncertain -> ReconciliationResponse(status = "uncertain")
    GitReconciliation.Absent -> ReconciliationResponse(status = "absent")
}
