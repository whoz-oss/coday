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
 * Precedence when an agent runs (decided in `AgentServiceImpl`): the agent's explicit `executionMode`
 * > the legacy `advancedExecution: true` flag (→ [ADVANCED]) > the model's
 * `AiModel.supportsAdvancedExecution` (→ [ADVANCED]) > [SIMPLE]. A null `executionMode` on an
 * [AgentConfigDto] or `AgentConfig` therefore means "no explicit choice, use the model's default".
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
