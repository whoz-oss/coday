package io.whozoss.agentos.exchange

import io.whozoss.agentos.sdk.api.integrationConfig.IntegrationTypeDescriptor

/**
 * Integration-type identifiers for the file exchange.
 *
 * [FILE_ACCESS] is the file-plugin's own type — the exchange points the plugin's tools at a
 * computed root. [CASE]/[NAMESPACE] are the **built-in** exchange integration types: enabling one
 * adds its key directly to the agent's `integrations` map (no persisted `IntegrationConfig`
 * instance), and the actual per-run tools are built imperatively in
 * [io.whozoss.agentos.agent.AgentServiceImpl.grantExchangeTools], from the scopes
 * `AgentServiceImpl.resolveCaseScope` and `resolveNamespaceScope` settle first (they hold the run's
 * `caseId`).
 * They are surfaced in `GET /api/integration-types` (with `builtIn = true`) only when the
 * file-plugin is loaded.
 */
object ExchangeIntegrationTypes {
    const val FILE_ACCESS = "FILE_ACCESS"
    const val CASE = "CASE_FILE_EXCHANGE"
    const val NAMESPACE = "NAMESPACE_FILE_EXCHANGE"

    /**
     * `configName` prefixes passed to the file-plugin — they become the tool-name prefix
     * `<configName>__<tool>` (e.g. `case-exchange__editFiles`). The frontend's exchange-mutation
     * matcher (case-chat) mirrors these literals, so keep the two in sync.
     */
    const val CASE_CONFIG_NAME = "case-exchange"
    const val NAMESPACE_CONFIG_NAME = "namespace-exchange"

    /**
     * The file-plugin tools that mutate their scope, unprefixed.
     *
     * Mirrored from the plugin, which owns them, for the one question the service cannot answer
     * otherwise: [ExchangeScopeGuidance] must not name a scope as the default write target when the
     * agent's own per-tool allowlist left it no way to write. The frontend's exchange-mutation
     * matcher mirrors the same literals (see [CASE_CONFIG_NAME]), so keep the three in sync.
     */
    val MUTATING_TOOLS = setOf("editFiles", "moveFile", "remove")

    /**
     * Tokens an integration's parameters may carry to name an exchange directory, resolved per run
     * by [ExchangePathRunIntegration].
     *
     * They are the upper-case spelling of [CASE_CONFIG_NAME] and [NAMESPACE_CONFIG_NAME] on
     * purpose: `{{CASE_EXCHANGE}}` designates exactly the directory the agent's own
     * `case-exchange__*` tools are rooted at. One vocabulary for the operator configuring an
     * integration and for the model reading a tool name.
     *
     * They name the exchange root itself, with no cleverness about Git. In an equipped family the
     * worktree is `repo/` underneath (see `docs/git-workspaces.md`), so an integration that must
     * run inside it writes `{{CASE_EXCHANGE}}/repo`. A token whose value silently changed meaning
     * depending on whether Git workspaces are enabled would be the kind of implicit behaviour this
     * codebase avoids elsewhere.
     *
     * They share the `{{TOKEN}}` spelling of
     * [io.whozoss.agentos.plugin.filesystem.NAMESPACE_CONFIG_PATH_TOKEN] but not its lifecycle:
     * that one is resolved once, when a filesystem YAML config is loaded; these are resolved on
     * every run, for configs of every origin, because the case directory only exists per run.
     */
    const val CASE_EXCHANGE_TOKEN = "{{CASE_EXCHANGE}}"
    const val NAMESPACE_EXCHANGE_TOKEN = "{{NAMESPACE_EXCHANGE}}"

    /**
     * Catalogue descriptors for the built-in exchange integrations (toggle-only, no config).
     *
     * The two flags come from [ExchangeToolsConfigProperties] and are published so a client can tell
     * what an agent that made no choice actually gets. They describe the fallback only: an agent that
     * declares the key wins in both directions.
     */
    fun builtInDescriptors(
        caseEnabledByDefault: Boolean,
        namespaceEnabledByDefault: Boolean,
    ): List<IntegrationTypeDescriptor> =
        listOf(
            IntegrationTypeDescriptor(
                type = CASE,
                displayName = "Case file exchange",
                description = "Read/write access to the files shared within the current case.",
                configSchema = null,
                builtIn = true,
                enabledByDefault = caseEnabledByDefault,
            ),
            IntegrationTypeDescriptor(
                type = NAMESPACE,
                displayName = "Namespace file exchange",
                description = "Read access to the namespace's shared files; read/write for namespace admins.",
                configSchema = null,
                builtIn = true,
                enabledByDefault = namespaceEnabledByDefault,
            ),
        )
}
