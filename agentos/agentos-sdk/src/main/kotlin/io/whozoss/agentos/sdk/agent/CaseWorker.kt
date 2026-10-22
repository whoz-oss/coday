package io.whozoss.agentos.sdk.agent

import io.whozoss.agentos.sdk.caseEvent.CaseEvent
import io.whozoss.agentos.sdk.entity.Entity
import kotlinx.coroutines.flow.Flow

/**
 * Contract the case runtime depends on to execute a turn.
 *
 * Anything that can receive the case's event history and produce a flow of new events.
 * Implementations: [Agent] (LLM-backed) and the zero-LLM loop orchestrator.
 *
 * Note: "CaseWorker" is provisional, subject to a later naming pass.
 */
interface CaseWorker : Entity {
    val name: String

    /**
     * Run the worker with the given case events.
     * Returns a Flow of CaseEvents that are emitted during execution:
     * - ThinkingEvent when processing
     * - ToolRequestEvent when calling a tool
     * - ToolResponseEvent when tool execution completes
     * - MessageEvent for agent responses
     * - AgentFinishedEvent when done
     *
     * @param events The full event history for this case.
     * @param shouldContinue A lambda the worker must poll before each LLM call and
     *   before each tool execution. When it returns false the worker must emit
     *   [io.whozoss.agentos.sdk.caseEvent.AgentFinishedEvent] and stop — this is how interrupt and kill signals
     *   propagate into the running worker without coroutine cancellation.
     */
    fun run(events: List<CaseEvent>, shouldContinue: () -> Boolean = { true }): Flow<CaseEvent>
}
