package io.biznet.agentos.orchestration

/**
 * Service for managing agent runtime instances.
 *
 * For now, works with hard-coded agent definitions.
 * Future: May integrate with plugin system or external configuration.
 */
interface IAgentService {
    /**
     * Find an agent by exact or partial name match.
     * Throws exception if not found or ambiguous.
     */
    fun findAgentByName(namePart: String): IAgent

    /**
     * Get the default agent for cases where no agent is explicitly selected.
     * Returns null if no default is configured.
     */
    fun getDefaultAgent(): IAgent?

    /**
     * Cleanup all agents and their resources.
     */
    suspend fun cleanup()

    /**
     * Immediately terminate all agents.
     */
    suspend fun kill()
}
