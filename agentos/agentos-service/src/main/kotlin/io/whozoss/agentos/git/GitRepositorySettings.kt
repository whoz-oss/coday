package io.whozoss.agentos.git

import com.fasterxml.jackson.annotation.JsonIgnoreProperties
import java.util.UUID

/**
 * The Git association of one namespace, parsed and validated from its `GIT_REPOSITORY`
 * configuration row.
 *
 * This is the only shape the provisioning code reads. It deliberately carries no secret: the
 * service account secret is resolved from [serviceAuthSettingId] at the moment it is needed, so
 * it never enters a snapshot, a log line or a persisted binding.
 *
 * A copy is frozen as JSON on each equipped family's binding. A field added later therefore needs a
 * default value, and a field is never renamed: bindings written before the change must still read.
 */
@JsonIgnoreProperties(ignoreUnknown = true)
data class GitRepositorySettings(
    /** Id of the backing `GIT_REPOSITORY` configuration row. */
    val configId: UUID,
    val namespaceId: UUID,
    val repositoryUrl: String,
    val mainBranch: String,
    /** UUID — never the name — of the namespace-shared AuthSetting used for every Git operation. */
    val serviceAuthSettingId: UUID,
    /**
     * Whether a new root case is equipped with its own detached worktree.
     *
     * Read once, when a root case is created, then persisted on that family's binding. Flipping it
     * later never re-equips existing families nor strips equipped ones.
     */
    val autoWorktreeForRootCases: Boolean,
    /** Optional setup command executed inside a new worktree before it is marked ready. */
    val setupCommand: String?,
)
