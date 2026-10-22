package io.whozoss.agentos.sdk.agent

/**
 * An LLM-backed [CaseWorker].
 *
 * Both [llmProvider] and [llmModel] are non-null by design: every concrete [Agent]
 * is backed by a specific AI provider and model. Workers that do not call an LLM
 * (e.g. the zero-LLM loop orchestrator) implement [CaseWorker] directly instead.
 */
interface Agent : CaseWorker {
    val llmProvider: String
    val llmModel: String
}
