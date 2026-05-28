package io.whozoss.agentos.agent

/**
 * Signals a misconfiguration of the AgentAdvanced confirmation flow at runtime —
 * typically a tool's [io.whozoss.agentos.sdk.tool.StandardTool.confirmationMode] is not NONE
 * but the agent was instantiated without a [ConfirmationManager].
 *
 * Distinct from [IllegalStateException] so the orchestrator can catch and log it at
 * the `error` level (it indicates a DI wiring bug that should surface in production
 * logs, not a per-run anomaly).
 */
class ConfirmationConfigurationException(
    message: String,
) : RuntimeException(message)
