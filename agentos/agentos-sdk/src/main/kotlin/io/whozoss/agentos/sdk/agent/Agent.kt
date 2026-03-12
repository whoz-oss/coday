package io.whozoss.agentos.sdk.agent

import io.whozoss.agentos.sdk.caseEvent.CaseEvent
import io.whozoss.agentos.sdk.entity.Entity
import kotlinx.coroutines.flow.Flow

interface Agent : Entity {
    val name: String

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
