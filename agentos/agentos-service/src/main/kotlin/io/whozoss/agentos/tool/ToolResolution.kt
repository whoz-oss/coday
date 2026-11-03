package io.whozoss.agentos.tool

import io.whozoss.agentos.sdk.tool.StandardTool

/**
 * Outcome of [ToolResolverService.resolveToolsForRun].
 *
 * Only integration **names** travel in [failedIntegrations], never the exception: a plugin failure can carry
 * a secret (URL, token) in its message, and a name cannot, so nothing downstream (the agent's instructions,
 * the case's events) can leak one by construction. The full failure is in the server logs.
 *
 * @property tools The de-duplicated tools of every integration that loaded, sorted by name.
 * @property failedIntegrations The names of the integrations whose plugin failed to provide their tools,
 *   in resolution order, each once. Empty when nothing failed.
 */
data class ToolResolution(
    val tools: Collection<StandardTool<*>>,
    val failedIntegrations: List<String>,
)
