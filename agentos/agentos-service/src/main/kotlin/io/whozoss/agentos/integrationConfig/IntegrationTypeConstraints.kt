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

    fun isNamespaceSingleton(integrationType: String?): Boolean = integrationType?.uppercase() in NAMESPACE_SINGLETON_TYPES

    /** The conversational `queryUser` tool. See `io.whozoss.agentos.queryUser.QueryUserToolPlugin`. */
    const val QUERY_USER_TYPE: String = "QUERY_USER"

    /** The USER_GROUP integration (membership + deployment queries). See `io.whozoss.agentos.userGroup.UserGroupToolPlugin`. */
    const val USER_GROUP_TYPE: String = "USER_GROUP"

    /**
     * The self-service configuration integration: an agent managing the **calling user's own**
     * [IntegrationConfig] overlays. See `io.whozoss.agentos.selfConfig.SelfConfigToolPlugin`.
     *
     * Deliberately absent from both [io.whozoss.agentos.integrationConfig.IntegrationsProperties.autoGrantableTypes]
     * and [io.whozoss.agentos.integrationConfig.IntegrationsProperties.userScopeDeniedTypes]:
     * an agent able to rewrite configuration must be an explicit, reviewed per-agent assignment
     * (so never auto-granted), but the type names no endpoint and carries no parameters, so a
     * user-scoped row of it redirects nothing (so no reason to deny the user scope either).
     */
    const val SELF_CONFIG_TYPE: String = "SELF_CONFIG"
}
