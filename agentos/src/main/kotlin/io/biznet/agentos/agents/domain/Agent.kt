package io.biznet.agentos.agents.domain

/**
 * Represents an AI agent in the agentOS orchestrator
 */
data class Agent(
    val id: String,
    val name: String,
    val description: String,
    val version: String,
    val capabilities: List<String>,
    val requiredContext: Set<ContextType>,
    val tags: Set<String> = emptySet(),
    val priority: Int = 0,
    val status: AgentStatus = AgentStatus.ACTIVE,
)

/**
 * Status of an agent
 */
enum class AgentStatus {
    ACTIVE,
    DEPRECATED,
    INACTIVE,
    MAINTENANCE,
}

/**
 * Types of context that an agent might require or be relevant for
 */
enum class ContextType {
    CODE_REVIEW,
    GENERAL,
    PERSONA,
    ROLES,
    SCREEN,
    TICKET,
    WORKSPACE,
}
