package io.whozoss.agentos.integrationConfig

/**
 * Structural rules that apply to an [IntegrationConfig] because of its `integrationType`.
 *
 * Most integration types are free-form: any scope, any number of rows per scope (JIRA_PROD next to
 * JIRA_STAGING). A few describe a capability of the namespace itself rather than a tool, and those
 * need tighter rules — currently only the Git association.
 *
 * The type literal lives here, not in the `git` package, so the persistence layer can apply the
 * constraint without depending on the Git domain.
 */
object IntegrationTypeConstraints {
    /** The namespace-to-repository association. See `io.whozoss.agentos.git.GitRepositoryIntegration`. */
    const val GIT_REPOSITORY_TYPE: String = "GIT_REPOSITORY"

    /**
     * Types allowed in exactly one active row per namespace, and only at namespace-shared scope
     * (`namespaceId != null && userId == null`).
     *
     * Both halves matter. Restricting the scope stops a member from shadowing the namespace
     * association with a personal overlay — [IntegrationConfigServiceImpl.findEffective] merges by
     * name across four layers, so a user-level row of the same name would otherwise win for that
     * user's runs. Restricting the count stops two associations from competing for one checkout.
     */
    val NAMESPACE_SINGLETON_TYPES: Set<String> = setOf(GIT_REPOSITORY_TYPE)

    fun isNamespaceSingleton(integrationType: String?): Boolean =
        integrationType?.uppercase() in NAMESPACE_SINGLETON_TYPES

    /** The conversational `queryUser` tool. See `io.whozoss.agentos.queryUser.QueryUserToolPlugin`. */
    const val QUERY_USER_TYPE: String = "QUERY_USER"

    /**
     * Types that may set [IntegrationConfig.autoGrant], i.e. be handed to every agent in the
     * config's scope without the agent naming it.
     *
     * The list deliberately starts closed, with a single conversational type. `autoGrant` at
     * platform scope hands a tool to *every agent of the environment at once*; on a type that
     * reaches the network, that is an unreviewed, environment-wide capability grant. The codebase
     * already carries this caution elsewhere: `IntegrationsProperties.userScopeDeniedTypes` forbids
     * user-level overlays of `HTTP_API`, `MCP_*` and `GIT` for a closely related reason.
     *
     * Opening the list when a use case justifies it costs one line. Starting open costs an
     * incident — and one that stays invisible until an agent uses a tool nobody meant to give it.
     */
    val AUTO_GRANTABLE_TYPES: Set<String> = setOf(QUERY_USER_TYPE)

    fun isAutoGrantable(integrationType: String?): Boolean = integrationType?.uppercase() in AUTO_GRANTABLE_TYPES
}
