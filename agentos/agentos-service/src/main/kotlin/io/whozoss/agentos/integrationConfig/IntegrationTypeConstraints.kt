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
}
