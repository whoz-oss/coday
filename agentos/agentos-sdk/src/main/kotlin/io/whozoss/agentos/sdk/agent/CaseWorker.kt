package io.whozoss.agentos.sdk.agent

import io.whozoss.agentos.sdk.caseEvent.CaseEvent
import io.whozoss.agentos.sdk.entity.Entity
import kotlinx.coroutines.flow.Flow

/**
 * Contract that a case runtime depends on to execute a turn.
 *
 * A [CaseWorker] is anything that can receive a list of [CaseEvent]s (the full
 * conversation history for the case) and produce a [Flow] of new [CaseEvent]s
 * as its response.
 *
 * Note: the name "CaseWorker" is provisional and subject to a future naming pass.
 *
 * Implementations include:
 * - [Agent] — a [CaseWorker] that also declares an LLM provider and model.
 * - [AgentLoop][io.whozoss.agentos.agent.AgentLoop] — a zero-LLM orchestrator that
 *   delegates work to child cases via a SEARCH → ACT workflow.
 *
 * @see Agent
 */
interface CaseWorker : Entity {
    /** Display name of this worker instance. */
    val name: String

    /**
     * Execute a single turn for the given event history.
     *
     * Returns a [Flow] of [CaseEvent]s emitted during execution (messages, tool
     * calls/responses, thinking events, finished events, etc.).
     *
     * @param events The full event history for this case.
     * @param shouldContinue A lambda the worker must poll before each LLM call and
     *   before each tool execution. When it returns false the worker must emit
     *   [io.whozoss.agentos.sdk.caseEvent.AgentFinishedEvent] and stop — this is how
     *   interrupt and kill signals propagate without coroutine cancellation.
     */
    fun run(events: List<CaseEvent>, shouldContinue: () -> Boolean = { true }): Flow<CaseEvent>
}
