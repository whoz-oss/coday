package io.whozoss.agentos.sdk.agent

import io.whozoss.agentos.sdk.aiProvider.ReasoningEffort
import io.whozoss.agentos.sdk.caseEvent.CaseEvent
import io.whozoss.agentos.sdk.entity.Entity
import kotlinx.coroutines.flow.Flow

interface Agent : Entity {
    val name: String
    val llmProvider: String
    val llmModel: String

    /** Reasoning effort the provider receives with this agent's model requests, or null when none is sent. */
    val reasoningEffort: ReasoningEffort?
        get() = null

    /**
     * Reasoning effort asked for this agent's model requests (the case's, else the model's), before the
     * provider's limits; null when none is asked. Differs from [reasoningEffort] when the provider caps the
     * level (vLLM, Ollama) or takes none (Anthropic, Gemini).
     */
    val requestedReasoningEffort: ReasoningEffort?
        get() = null

    /**
     * Run the agent with the given case events.
     * Returns a Flow of CaseEvents that are emitted during execution:
     * - ThinkingEvent when processing
     * - ToolRequestEvent when calling a tool
     * - ToolResponseEvent when tool execution completes
     * - MessageEvent for agent responses
     * - AgentFinishedEvent when done
     *
     * @param events The full event history for this case.
     * @param shouldContinue A lambda the agent must poll before each LLM call and
     *   before each tool execution. When it returns false the agent must emit
     *   [AgentFinishedEvent] and stop — this is how interrupt and kill signals
     *   propagate into the running agent without coroutine cancellation.
     */
    fun run(events: List<CaseEvent>, shouldContinue: () -> Boolean = { true }): Flow<CaseEvent>
}
