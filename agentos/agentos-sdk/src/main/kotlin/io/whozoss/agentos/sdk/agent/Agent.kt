package io.whozoss.agentos.sdk.agent

/**
 * A [CaseWorker] that delegates to a real LLM provider.
 *
 * Extends [CaseWorker] with the provider and model identity needed to record
 * telemetry, build chat clients, and populate usage records.
 *
 * Both [llmProvider] and [llmModel] are non-null: every [Agent] implementation
 * must have a concrete provider and model. Implementations that do not call an LLM
 * (e.g. [io.whozoss.agentos.agent.AgentLoop]) implement [CaseWorker] directly instead.
 */
interface Agent : CaseWorker {
    /** Name of the AI provider (e.g. `"anthropic"`, `"openai"`). */
    val llmProvider: String

    /** API model name used for LLM calls (e.g. `"claude-sonnet-4-5"`). */
    val llmModel: String
}
