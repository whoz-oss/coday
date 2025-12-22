package io.biznet.agentos.service.service

import io.biznet.agentos.sdk.AgentInput
import io.biznet.agentos.sdk.AgentMetadata
import io.biznet.agentos.sdk.AgentOutput
import io.biznet.agentos.sdk.AgentPlugin
import org.pf4j.PluginManager
import org.springframework.stereotype.Service

/**
 * Service for managing and executing agents.
 */
@Service
class AgentService(
    private val pluginManager: PluginManager,
) {
    /**
     * List all available agents.
     */
    fun listAgents(): List<AgentMetadata> =
        pluginManager
            .getExtensions(AgentPlugin::class.java)
            .map { it.getMetadata() }

    /**
     * Execute a specific agent by name.
     */
    suspend fun executeAgent(
        agentName: String,
        input: AgentInput,
    ): AgentOutput {
        val agent =
            pluginManager
                .getExtensions(AgentPlugin::class.java)
                .firstOrNull { it.getMetadata().name == agentName }
                ?: throw IllegalArgumentException("Agent not found: $agentName")

        return agent.execute(input)
    }
}
