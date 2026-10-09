package io.whozoss.agentos.sdk.api.agentConfig

/**
 * Execution mode for an agent, controlling how it processes requests.
 *
 * - [SIMPLE]: Single LLM call per turn; the LLM decides which tools to invoke.
 * - [ADVANCED]: Multi-step loop with explicit intention generation, parameter generation,
 *   optional confirmation gate, and final response generation. All orchestration is
 *   LLM-driven but with richer structure than [SIMPLE].
 * - [LOOP]: **Experimental.** Zero LLM calls. A predefined sequence of tools is executed
 *   programmatically. Used by AgentLoop to orchestrate agentic workflows at scale without
 *   involving any LLM.
 *
 * [SIMPLE] and [ADVANCED] are the stable replacement of `advancedExecution`; only [LOOP] is
 * experimental.
 *
 * Replaces the legacy `advancedExecution: Boolean` flag.
 * Backward compatibility: when [executionMode] is null on an [AgentConfigDto] or [AgentConfig],
 * the legacy [AgentConfig.advancedExecution] field is used as a fallback
 * (`true` → [ADVANCED], `false` → [SIMPLE]).
 */
enum class ExecutionMode {
    SIMPLE,
    ADVANCED,

    /**
     * Experimental: proof of concept of programmatic loops. May change or be removed without notice,
     * independently of the [SIMPLE] / [ADVANCED] contract.
     */
    LOOP,
}
