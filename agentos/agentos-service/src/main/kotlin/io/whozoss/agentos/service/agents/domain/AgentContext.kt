package io.whozoss.agentos.service.agents.domain

/**
 * Represents the context in which agents should be queried
 */
data class AgentContext(
    val contextTypes: Set<ContextType> = emptySet(),
    val tags: Set<String> = emptySet(),
    val capabilities: Set<String> = emptySet(),
    val excludeStatuses: Set<AgentStatus> = emptySet(),
    val minPriority: Int? = null,
    val maxResults: Int? = null
) {
    companion object {
        fun empty() = AgentContext()
        
        fun forContextType(vararg types: ContextType) = AgentContext(
            contextTypes = types.toSet()
        )
        
        fun forCapability(vararg capabilities: String) = AgentContext(
            capabilities = capabilities.toSet()
        )
    }
}

/**
 * Response containing filtered agents with metadata
 */
data class AgentQueryResponse(
    val agents: List<Agent>,
    val totalCount: Int,
    val context: AgentContext
)
