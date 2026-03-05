package io.whozoss.agentos.agent

import io.whozoss.agentos.sdk.agent.Agent

/**
 * Service for managing agent runtime instances.
 *
 * For now, works with hard-coded agent definitions.
 * Future: May integrate with plugin system or external configuration.
 */
interface AgentService {
    /**
     * Find an agent by exact or partial name match.
     * Throws exception if not found or ambiguous.
     */
    fun findAgentByName(namePart: String): Agent

    fun listAgents(): List<Agent>

    /**
     * Get the default agent for cases where no agent is explicitly selected.
     * Returns null if no default is configured.
     */
    fun getDefaultAgent(): Agent?

    /**
     * Get the name of the default agent without instantiating a full Agent.
     * Use this when only the agent's identity is needed (e.g. to emit an AgentSelectedEvent).
     * Returns null if no default is configured.
     */
    fun getDefaultAgentName(): String?

    /**
     * Resolve an agent's canonical name by partial name match, without instantiating a full Agent.
     * Use this when only the agent's identity is needed (e.g. to emit an AgentSelectedEvent).
     * Returns null if no match is found.
     */
    fun resolveAgentName(namePart: String): String?
}
