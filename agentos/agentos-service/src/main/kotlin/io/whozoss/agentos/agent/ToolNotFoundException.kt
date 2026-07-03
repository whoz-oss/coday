package io.whozoss.agentos.agent

/**
 * Signals that an intention referenced a tool name that is not registered with the
 * current `AgentAdvancedContext.tools`. Typically an ops-relevant inconsistency:
 *  - tool registry desync (plugin not loaded, version skew),
 *  - LLM hallucinated a tool name that does not exist for this agent,
 *  - intention generator returning a stale/aliased name.
 *
 * Distinct from [IllegalStateException] so the orchestrator can catch and log it at
 * the `error` level (it indicates a config/runtime inconsistency that should surface
 * in production logs, not be silently re-thrown).
 */
class ToolNotFoundException(
    toolName: String,
) : RuntimeException("Tool not found: $toolName")
