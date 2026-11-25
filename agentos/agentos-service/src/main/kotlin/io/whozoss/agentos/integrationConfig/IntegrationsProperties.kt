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
 * `BASH` is denied for a related reason: its commands are arbitrary, and they receive the secret of the bound Auth
 * Setting as environment variables. A user overlay of a shared `BASH` config (which inherits its `authSettingName`),
 * or a user config naming a namespace-shared Auth Setting, could therefore run a command that exfiltrates a shared
 * credential.
 *
 * `GRADLE` is denied for the same reason as `BASH`, and worse: a user overlay of a shared `GRADLE` config could set
 * `javaHome` (`gradlew` runs `$JAVA_HOME/bin/java`), any absolute `projectDirectory`, and `inheritEnvironment`, which
 * hands environment variables of the service (LLM keys, tokens) to the build and its tests. That is code execution
 * and secret exfiltration with the rights of the service.
 *
 * `CASE` is deliberately not on the list. Its parameters name no endpoint, credential or command, so an overlay
 * cannot redirect anything: it can only narrow or widen which agents the starter tools may start. That is bounded
 * by the service, which only starts an agent deployed to the target user, and `FreeCaseStarterForUser` requires
 * its caller to be ADMIN of the namespace. A user may therefore define a `CASE` config of their own, for example
 * to restrict the perimeter of their own runs.
 *
 * Override with environment variable (Spring Boot relaxed binding):
 * - `AGENTOS_INTEGRATIONS_USER_SCOPE_DENIED_TYPES` (comma-separated, replaces the default list)
 *
 * The default list and its rationale are documented in `application.yml` (`agentos.integrations`).
 */
@ConfigurationProperties(prefix = "agentos.integrations")
data class IntegrationsProperties(
    /**
     * Integration types (exact match on `integrationType`, as in the plugin registry) that no user
     * may create or update in a user scope. Setting the list replaces the default entirely: an
     * instance that wants to allow one of the defaults must list the others explicitly.
     */
    val userScopeDeniedTypes: List<String> = listOf("HTTP_API", "MCP_STDIO", "MCP_HTTP", "GIT", "BASH", "GRADLE", "KTLINT"),
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
     * `HTTP_API`, `MCP_*`, `GIT`, `BASH` and `GRADLE` for a closely related reason.
     *
     * Override with environment variable (Spring Boot relaxed binding):
     * - `AGENTOS_INTEGRATIONS_AUTO_GRANTABLE_TYPES` (comma-separated, replaces the default list)
     */
    val autoGrantableTypes: List<String> = listOf(IntegrationTypeConstraints.QUERY_USER_TYPE),
    /**
     * The environment tokens integration parameters may carry, resolved per run by
     * [EnvironmentTokenRunIntegration]: each key `NAME` declares the token `{{NAME}}`, and its value is what
     * the token stands for. The value is where the deployment feeds the environment in, typically a Spring
     * placeholder with a default, so that an integration is written once and works on every instance:
     *
     * ```yaml
     * agentos:
     *   integrations:
     *     environment-tokens:
     *       "[FACTORY_URL]": ${FACTORY_URL:}
     *       "[NAMESPACE_PATH]": ${SOME_ENV:/default/path}
     * ```
     *
     * ## An explicit allowlist, deliberately
     *
     * This is a closed list of declared tokens and never a lookup of arbitrary Spring properties or
     * environment variables. Integration parameters are written by namespace admins, and by users for some
     * types: a `{{ANY_PROPERTY}}` that read the Spring Environment would let them put `AGENTOS_ENCRYPTION_KEY`,
     * a database password or a provider API key into a URL they control or a command they run, and read it
     * back from the outside. Only what an operator chose to expose here is reachable, and an operator exposes
     * a value, not a secret.
     *
     * ## Which keys count
     *
     * A key must match `[A-Z][A-Z0-9_]*`. A key that does not, and a key that collides with a token that has
     * a meaning of its own (`CASE_EXCHANGE`, `NAMESPACE_EXCHANGE`, `NAMESPACE_CONFIG_PATH`), is ignored with a
     * warning logged once, at startup. A key whose value is blank (the environment variable is not set) is
     * kept: an integration that uses it is left out of the run rather than handed the literal token.
     *
     * Keys are kept as written only through the bracket notation of the YAML above; Spring otherwise drops
     * the upper-case letters and underscores of a map key. The environment variable form
     * `AGENTOS_INTEGRATIONS_ENVIRONMENT_TOKENS_<NAME>` is read as the name written in lower case, with its
     * underscores turned into dots, and [EnvironmentTokenRunIntegration] normalises such a key back to
     * `<NAME>`.
     */
    val environmentTokens: Map<String, String> = emptyMap(),
) {
    private val autoGrantableUpper: Set<String> = autoGrantableTypes.mapTo(mutableSetOf()) { it.uppercase() }

    fun isAutoGrantable(integrationType: String?): Boolean = integrationType?.uppercase() in autoGrantableUpper
}
