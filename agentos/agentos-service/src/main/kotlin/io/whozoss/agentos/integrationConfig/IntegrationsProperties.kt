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
 * network endpoint (`baseUrl`, `url`, a command to spawn), that overlay would let any authenticated
 * user redirect the shared credential to a host they control. Those types are therefore refused in
 * the two user scopes at the API edge; platform and namespace-shared configs are unaffected.
 *
 * Override with environment variables (Spring Boot relaxed binding):
 * - `AGENTOS_INTEGRATIONS_USER_SCOPE_DENIED_TYPES` (comma-separated, replaces the default list)
 * - `AGENTOS_INTEGRATIONS_PREVIEW_PROVIDE_TOOLS_TIMEOUT_MS`
 * - `AGENTOS_INTEGRATIONS_PREVIEW_DESCRIBE_NAMESPACE_TIMEOUT_MS`
 *
 * Example (application.yml):
 * ```yaml
 * agentos:
 *   integrations:
 *     user-scope-denied-types: HTTP_API,MCP_STDIO,MCP_HTTP
 *     preview-provide-tools-timeout-ms: 30000
 *     preview-describe-namespace-timeout-ms: 5000
 * ```
 */
@ConfigurationProperties(prefix = "agentos.integrations")
data class IntegrationsProperties(
    /**
     * Integration types (exact match on `integrationType`, as in the plugin registry) that no user
     * may create or update in a user scope. Setting the list replaces the default entirely: an
     * instance that wants to allow one of the defaults must list the others explicitly.
     */
    val userScopeDeniedTypes: List<String> = listOf("HTTP_API", "MCP_STDIO", "MCP_HTTP"),
    /**
     * Upper bound, in milliseconds, on how long the tool preview
     * (`POST /api/integration-configs/{id}/preview-tools`) waits for a plugin's `describeNamespace`
     * line; past it the line is reported as absent. The request returns at the bound; the call is
     * cancelled, and a plugin that blocks instead of suspending keeps its preview worker until it returns.
     */
    val previewDescribeNamespaceTimeoutMs: Long = 5_000,
    /**
     * Upper bound, in milliseconds, on how long the tool preview waits for a plugin's `provideTools`,
     * whatever timeouts the config itself declares (an `MCP_HTTP` config bounds `initialize` and
     * `listTools` by the larger of `timeoutSeconds` and `toolCallTimeoutSeconds`, 60 s by default);
     * past it the preview reports a timeout in `error`. The request returns at the bound; the plugin
     * call is abandoned without being interrupted and keeps its preview worker until it returns, so
     * a slow server that an agent run would still reach can time out here: raise the value for it.
     */
    val previewProvideToolsTimeoutMs: Long = 30_000,
)
