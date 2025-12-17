package io.biznet.agentos.sdk

import org.pf4j.ExtensionPoint

/**
 * Base interface for Agent plugins.
 * All agent implementations must implement this interface.
 */
interface AgentPlugin : ExtensionPoint {
    /**
     * Execute the agent with the given input.
     * 
     * @param input The input data for the agent
     * @return The agent's response
     */
    suspend fun execute(input: AgentInput): AgentOutput
    
    /**
     * Get the agent's metadata (name, description, capabilities).
     */
    fun getMetadata(): AgentMetadata
}

/**
 * Input data for agent execution.
 */
data class AgentInput(
    val message: String,
    val context: Map<String, Any> = emptyMap(),
    val conversationId: String? = null
)

/**
 * Output data from agent execution.
 */
data class AgentOutput(
    val message: String,
    val metadata: Map<String, Any> = emptyMap(),
    val conversationId: String? = null
)

/**
 * Metadata about an agent.
 */
data class AgentMetadata(
    val name: String,
    val description: String,
    val version: String,
    val capabilities: List<String> = emptyList()
)
