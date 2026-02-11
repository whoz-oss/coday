package io.whozoss.agentos.agent

import io.whozoss.agentos.sdk.agent.AgentDefinition
import io.whozoss.agentos.sdk.agent.AgentStatus

/**
 * Represents the context in which agents should be queried
 */
data class AgentContext(
    val contextTypes: Set<String> = emptySet(),
    val tags: Set<String> = emptySet(),
    val capabilities: Set<String> = emptySet(),
    val excludeStatuses: Set<AgentStatus> = emptySet(),
    val minPriority: Int? = null,
    val maxResults: Int? = null,
)

/**
 * Response containing filtered agents with metadata
 */
data class AgentQueryResponse(
    val agents: List<AgentDefinition>,
    val totalCount: Int,
    val context: AgentContext,
)
