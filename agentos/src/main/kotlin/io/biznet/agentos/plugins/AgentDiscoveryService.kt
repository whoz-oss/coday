package io.biznet.agentos.plugins

import io.biznet.agentos.agents.domain.Agent
import io.biznet.agentos.api.agent.AgentPlugin
import org.pf4j.PluginManager
import org.slf4j.LoggerFactory
import org.springframework.stereotype.Service

/**
 * Service responsible for discovering and extracting Agents from loaded plugins.
 */
@Service
class AgentDiscoveryService(
    private val pluginManager: PluginManager
) {
    private val logger = LoggerFactory.getLogger(AgentDiscoveryService::class.java)

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
}