package io.whozoss.agentos.integrationConfig

import io.whozoss.agentos.sdk.tool.ConfirmationMode

/**
 * Result of resolving the tools of one [IntegrationConfig] on behalf of a user, outside any case.
 *
 * Either [tools] is populated (and [error] is null) or the plugin failed to build its tool set and
 * [error] carries the failure (`ExceptionClass: message`) with [tools] empty. [namespaceDescription]
 * is independent of both: it is the plugin's `describeNamespace` line, null when the plugin gives
 * none, fails or does not answer within [IntegrationsProperties.previewDescribeNamespaceTimeoutMs].
 */
data class IntegrationConfigToolPreview(
    val integrationType: String,
    val configName: String,
    val namespaceDescription: String?,
    val tools: List<ToolPreview>,
    val error: String?,
) {
    /** One tool as the agent would see it: identity, prompt-facing description, input contract, confirmation gate. */
    data class ToolPreview(
        val name: String,
        val description: String,
        val inputSchema: String,
        val confirmationMode: ConfirmationMode,
    )
}
