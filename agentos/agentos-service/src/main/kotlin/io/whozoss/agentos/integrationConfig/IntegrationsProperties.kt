package io.whozoss.agentos.integrationConfig

import org.springframework.boot.context.properties.ConfigurationProperties

/**
 * Platform-level policy on which [IntegrationConfig] scopes each integration type may be created in.
 *
 * Bound from the `agentos.integrations` prefix in application.yml.
 *
 * A user-scoped config (user-global or user × namespace) with the same name as a platform or
 * namespace-shared one is deep-merged over it at run time and inherits its `authSettingName`
 * (see `IntegrationConfigMergeStrategy`). For an integration type whose parameters point at a
 * network endpoint (`baseUrl`, `url`, `repositoryUrl`, a command to spawn), that overlay would let any authenticated
 * user redirect the shared credential to a host they control. Those types are therefore refused in
 * the two user scopes at the API edge; platform and namespace-shared configs are unaffected.
 *
 * Override with environment variable (Spring Boot relaxed binding):
 * - `AGENTOS_INTEGRATIONS_USER_SCOPE_DENIED_TYPES` (comma-separated, replaces the default list)
 *
 * Example (application.yml):
 * ```yaml
 * agentos:
 *   integrations:
 *     user-scope-denied-types: HTTP_API,MCP_STDIO,MCP_HTTP,GIT
 * ```
 */
@ConfigurationProperties(prefix = "agentos.integrations")
data class IntegrationsProperties(
    /**
     * Integration types (exact match on `integrationType`, as in the plugin registry) that no user
     * may create or update in a user scope. Setting the list replaces the default entirely: an
     * instance that wants to allow one of the defaults must list the others explicitly.
     */
    val userScopeDeniedTypes: List<String> = listOf("HTTP_API", "MCP_STDIO", "MCP_HTTP", "GIT"),
    /**
     * Integration types that may set [IntegrationConfig.autoGrant], i.e. be handed to every agent
     * in the config's scope without the agent naming it. Setting the list replaces the default
     * entirely: an instance that wants to allow an additional type must list
     * [IntegrationTypeConstraints.QUERY_USER_TYPE] explicitly alongside it.
     *
     * The list deliberately starts closed, with a single conversational type. `autoGrant` at
     * platform scope hands a tool to *every agent of the environment at once*; on a type that
     * reaches the network, that is an unreviewed, environment-wide capability grant. The codebase
     * already carries this caution elsewhere: [userScopeDeniedTypes] forbids user-level overlays of
     * `HTTP_API`, `MCP_*` and `GIT` for a closely related reason.
     *
     * Override with environment variable (Spring Boot relaxed binding):
     * - `AGENTOS_INTEGRATIONS_AUTO_GRANTABLE_TYPES` (comma-separated, replaces the default list)
     */
    val autoGrantableTypes: List<String> = listOf(IntegrationTypeConstraints.QUERY_USER_TYPE),
) {
    private val autoGrantableUpper: Set<String> = autoGrantableTypes.mapTo(mutableSetOf()) { it.uppercase() }

    fun isAutoGrantable(integrationType: String?): Boolean = integrationType?.uppercase() in autoGrantableUpper
}
