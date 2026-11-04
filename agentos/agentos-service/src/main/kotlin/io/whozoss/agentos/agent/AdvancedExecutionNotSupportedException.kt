package io.whozoss.agentos.agent

/**
 * An agent configured in ADVANCED execution mode was resolved on an AI model that does not declare
 * `supportsAdvancedExecution`.
 *
 * It is a configuration error, not a per-run anomaly: the agent is never silently downgraded to SIMPLE.
 * The message names the agent, the model and the fix, and is meant to be shown to the user, which is why
 * the case runtime surfaces it as a warning event (see `CaseServiceImpl.runAgent`).
 */
class AdvancedExecutionNotSupportedException(
    message: String,
) : IllegalStateException(message)
