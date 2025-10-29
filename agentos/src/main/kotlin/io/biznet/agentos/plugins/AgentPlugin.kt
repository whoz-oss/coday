package io.biznet.agentos.plugins

import io.biznet.agentos.agents.domain.Agent
import org.pf4j.ExtensionPoint

/**
 * Extension point for agent plugins.
 * 
 * Plugin developers implement this interface to provide agents to agentOS.
 * Each plugin can contribute multiple agents.
 * 
 * Example implementation:
 * ```kotlin
 * @Extension
 * class MyAgentPlugin : AgentPlugin {
 *     override fun getPluginId(): String = "my-agent-plugin"
 *     
 *     override fun getAgents(): List<Agent> = listOf(
 *         Agent(
 *             id = "my-agent",
 *             name = "My Agent",
 *             // ... other properties
 *         )
 *     )
 * }
 * ```
 */
interface AgentPlugin : ExtensionPoint {
    
    /**
     * Unique identifier for this plugin
     */
    fun getPluginId(): String
    
    /**
     * List of agents provided by this plugin
     */
    fun getAgents(): List<Agent>
    
    /**
     * Plugin version (optional, defaults to "1.0.0")
     */
    fun getVersion(): String = "1.0.0"
    
    /**
     * Plugin description (optional)
     */
    fun getDescription(): String = ""
    
    /**
     * Initialize the plugin (optional)
     * Called when the plugin is loaded
     */
    fun initialize() {}
    
    /**
     * Cleanup resources (optional)
     * Called when the plugin is unloaded
     */
    fun destroy() {}
}
