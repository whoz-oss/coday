package io.whozoss.agentos.agent

import io.whozoss.agentos.integrationConfig.IntegrationConfig

/**
 * Adjusts the integrations of one agent run, for a feature that binds a tool to the case being run.
 * It returns per-run copies: the saved configurations are never rewritten.
 */
fun interface RunIntegrationCustomizer {
    fun customize(
        configs: List<IntegrationConfig>,
        context: AgentExecutionContext,
    ): List<IntegrationConfig>
}
