package io.whozoss.agentos.service.plugins

import io.whozoss.agentos.sdk.agent.AgentPlugin
import io.whozoss.agentos.service.agents.domain.Agent
import mu.KLogging
import org.pf4j.PluginManager
import org.springframework.stereotype.Service

/**
 * Service responsible for discovering and extracting Agents from loaded plugins.
 */
@Service
class AgentDiscoveryService(
    private val pluginManager: PluginManager,
) {
    /**
     * Get all agents from all loaded plugins
     */
    fun discoverAgents(): List<Agent> {
        logger.info("Searching for AgentPlugin extensions...")

        val extensions = pluginManager.getExtensions(AgentPlugin::class.java)
        logger.info("Found ${extensions.size} AgentPlugin extension(s) total")

        if (extensions.isEmpty()) {
            logger.warn("No AgentPlugin extensions found.")
        }

        val agents = mutableListOf<Agent>()

        extensions.forEach { plugin ->
            try {
                logger.debug("Loading agents from plugin: ${plugin.getPluginId()}")
                val pluginAgents = plugin.getAgents()
                agents.addAll(pluginAgents)
                logger.info("Loaded ${pluginAgents.size} agent(s) from plugin '${plugin.getPluginId()}'")
            } catch (e: Exception) {
                logger.error("Failed to load agents from plugin '${plugin.getPluginId()}': ${e.message}", e)
            }
        }

        return agents
    }

    companion object : KLogging()
}
