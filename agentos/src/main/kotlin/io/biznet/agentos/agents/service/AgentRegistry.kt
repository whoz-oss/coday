package io.biznet.agentos.agents.service

import io.biznet.agentos.agents.domain.Agent
import io.biznet.agentos.agents.domain.AgentContext
import io.biznet.agentos.agents.domain.AgentQueryResponse
import io.biznet.agentos.agents.domain.ContextType
import io.biznet.agentos.plugins.PluginService
import jakarta.annotation.PostConstruct
import org.slf4j.LoggerFactory
import org.springframework.stereotype.Service

/**
 * Registry service for managing and querying agents based on context.
 * Integrates with the plugin system to load agents from external plugins.
 */
@Service
class AgentRegistry(
    private val pluginService: PluginService,
) {
    private val logger = LoggerFactory.getLogger(AgentRegistry::class.java)
    private val agents = mutableMapOf<String, Agent>()

    @PostConstruct
    fun initialize() {
        // Initialize with default agents
        registerDefaultAgents()

        // Load agents from plugins
        loadAgentsFromPlugins()
    }

    /**
     * Register a new agent in the system
     */
    fun registerAgent(agent: Agent): Agent {
        agents[agent.id] = agent
        return agent
    }

    /**
     * Unregister an agent from the system
     */
    fun unregisterAgent(agentId: String): Boolean = agents.remove(agentId) != null

    /**
     * Get an agent by ID
     */
    fun getAgent(agentId: String): Agent? = agents[agentId]

    /**
     * Get all registered agents
     */
    fun getAllAgents(): List<Agent> = agents.values.toList()

    /**
     * Find agents matching the given context
     */
    fun findAgents(context: AgentContext): AgentQueryResponse {
        var filteredAgents = agents.values.asSequence()

        // Filter by status
        if (context.excludeStatuses.isNotEmpty()) {
            filteredAgents = filteredAgents.filter { it.status !in context.excludeStatuses }
        }

        // Filter by context types
        if (context.contextTypes.isNotEmpty()) {
            filteredAgents =
                filteredAgents.filter { agent ->
                    agent.requiredContext.any { it in context.contextTypes }
                }
        }

        // Filter by capabilities
        if (context.capabilities.isNotEmpty()) {
            filteredAgents =
                filteredAgents.filter { agent ->
                    agent.capabilities.any { it in context.capabilities }
                }
        }

        // Filter by tags
        if (context.tags.isNotEmpty()) {
            filteredAgents =
                filteredAgents.filter { agent ->
                    agent.tags.any { it in context.tags }
                }
        }

        // Filter by minimum priority
        if (context.minPriority != null) {
            filteredAgents = filteredAgents.filter { it.priority >= context.minPriority }
        }

        // Sort by priority (descending)
        filteredAgents = filteredAgents.sortedByDescending { it.priority }

        // Apply max results limit
        val resultList =
            if (context.maxResults != null) {
                filteredAgents.take(context.maxResults).toList()
            } else {
                filteredAgents.toList()
            }

        return AgentQueryResponse(
            agents = resultList,
            totalCount = resultList.size,
            context = context,
        )
    }

    /**
     * Update an existing agent
     */
    fun updateAgent(
        agentId: String,
        updatedAgent: Agent,
    ): Agent? {
        if (!agents.containsKey(agentId)) {
            return null
        }
        agents[agentId] = updatedAgent.copy(id = agentId)
        return agents[agentId]
    }

    /**
     * Load agents from all plugins
     */
    fun loadAgentsFromPlugins() {
        logger.info("Loading agents from plugins...")
        try {
            val pluginAgents = pluginService.getAgentsFromPlugins()
            pluginAgents.forEach { agent ->
                registerAgent(agent)
                logger.debug("Registered agent '${agent.id}' from plugin")
            }
            logger.info("Loaded ${pluginAgents.size} agent(s) from plugins")
        } catch (e: Exception) {
            logger.error("Failed to load agents from plugins: ${e.message}", e)
        }
    }

    /**
     * Reload agents from plugins (clear plugin agents and reload)
     */
    fun reloadPluginAgents() {
        logger.info("Reloading agents from plugins...")
        // Note: In a real implementation, you might want to track which agents came from plugins
        // For now, we'll just reload all plugin agents
        loadAgentsFromPlugins()
    }

    /**
     * Register default agents for demonstration
     */
    private fun registerDefaultAgents() {
        registerAgent(
            Agent(
                id = "general-purpose",
                name = "All purpose default agent",
                description = "Default agent without any particular purpose, should delegate specific tasks to other agents",
                version = "1.0.0",
                capabilities = listOf("general", "conversation", "delegation"),
                requiredContext = setOf(ContextType.GENERAL),
                tags = setOf("general", "conversation", "delegation"),
                priority = 100,
            ),
        )
    }
}
