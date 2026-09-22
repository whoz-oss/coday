package io.whozoss.agentos.git

import io.whozoss.agentos.integrationConfig.IntegrationTypeConstraints

/**
 * The `GIT_REPOSITORY` integration: the optional association between a namespace and one Git
 * repository.
 *
 * It is stored as an [io.whozoss.agentos.integrationConfig.IntegrationConfig] to reuse the existing
 * CRUD, scoping and audit, but it is **not** a tool integration: no `ToolPlugin` answers this type,
 * and it must never be offered as an agent-selectable integration. Two rules follow, enforced in
 * [io.whozoss.agentos.integrationConfig.IntegrationConfigServiceImpl]:
 *
 * - **Scope**: namespace-shared only (`namespaceId != null && userId == null`). A personal overlay
 *   of this type would let a member point provisioning at a repository of their choosing.
 * - **Singleton**: at most one active row per namespace, enforced by a database constraint on
 *   [io.whozoss.agentos.integrationConfig.IntegrationConfigNode.singletonKey] rather than by an
 *   application check that concurrency can defeat.
 */
object GitRepositoryIntegration {
    /** Value of [io.whozoss.agentos.integrationConfig.IntegrationConfig.integrationType]. */
    const val TYPE: String = IntegrationTypeConstraints.GIT_REPOSITORY_TYPE

    /** HTTPS URL of the repository to clone. */
    const val PARAM_REPOSITORY_URL: String = "repositoryUrl"

    /** Remote branch fetched when freezing the base of each new case worktree. */
    const val PARAM_MAIN_BRANCH: String = "mainBranch"

    /** UUID of the namespace-shared AuthSetting holding the service account secret. */
    const val PARAM_SERVICE_AUTH_SETTING_ID: String = "serviceAuthSettingId"

    /** Whether a new root case gets its own detached worktree. Off unless explicitly enabled. */
    const val PARAM_AUTO_WORKTREE: String = "autoWorktreeForRootCases"

    /** Optional command run once inside a freshly created worktree (dependency install, codegen). */
    const val PARAM_SETUP_COMMAND: String = "setupCommand"

    /** Default when [PARAM_MAIN_BRANCH] is absent. */
    const val DEFAULT_MAIN_BRANCH: String = "main"
}
