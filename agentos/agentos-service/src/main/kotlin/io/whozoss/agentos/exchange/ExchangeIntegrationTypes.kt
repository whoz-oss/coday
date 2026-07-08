package io.whozoss.agentos.exchange

import io.whozoss.agentos.integrationConfig.IntegrationTypeDescriptor

/**
 * Integration-type identifiers for the file exchange.
 *
 * [FILE_ACCESS] is the file-plugin's own type — the exchange points the plugin's tools at a
 * computed root. [CASE]/[NAMESPACE] are the **built-in** exchange integration types: enabling one
 * adds its key directly to the agent's `integrations` map (no persisted `IntegrationConfig`
 * instance), and the actual per-run tools are built imperatively in
 * [io.whozoss.agentos.agent.AgentServiceImpl.buildExchangeTools] (which has the run's `caseId`).
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

    /** Catalogue descriptors for the built-in exchange integrations (toggle-only, no config). */
    fun builtInDescriptors(): List<IntegrationTypeDescriptor> =
        listOf(
            IntegrationTypeDescriptor(
                type = CASE,
                displayName = "Case file exchange",
                description = "Read/write access to the files shared within the current case.",
                configSchema = null,
                builtIn = true,
            ),
            IntegrationTypeDescriptor(
                type = NAMESPACE,
                displayName = "Namespace file exchange",
                description = "Read access to the namespace's shared files; read/write for namespace admins.",
                configSchema = null,
                builtIn = true,
            ),
        )
}
