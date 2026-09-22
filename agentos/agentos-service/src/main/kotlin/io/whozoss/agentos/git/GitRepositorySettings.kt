package io.whozoss.agentos.git

import java.util.UUID

/**
 * The Git association of one namespace, parsed and validated from its `GIT_REPOSITORY`
 * configuration row.
 *
 * This is the only shape the provisioning code reads. It deliberately carries no secret: the
 * service account secret is resolved from [serviceAuthSettingId] at the moment it is needed, so
 * it never enters a snapshot, a log line or a persisted binding.
 */
data class GitRepositorySettings(
    /** Id of the backing `GIT_REPOSITORY` configuration row. */
    val configId: UUID,
    val namespaceId: UUID,
    val repositoryUrl: String,
    val mainBranch: String,
    /** UUID — never the name — of the namespace-shared AuthSetting used for every Git operation. */
    val serviceAuthSettingId: UUID,
)
