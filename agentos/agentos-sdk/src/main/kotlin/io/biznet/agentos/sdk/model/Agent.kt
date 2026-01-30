package io.biznet.agentos.sdk.model

import io.biznet.agentos.sdk.entity.Entity
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
     */
    fun run(events: List<CaseEvent>): Flow<CaseEvent>
}
