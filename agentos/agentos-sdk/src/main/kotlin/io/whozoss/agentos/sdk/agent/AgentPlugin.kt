package io.whozoss.agentos.sdk.agent

import org.pf4j.ExtensionPoint

/**
 * Extension point for AgentOS plugins.
 * 
 * Plugin developers implement this interface to provide agents to AgentOS.
 * Each plugin can contribute multiple agents with different capabilities.
 * 
 * ## Usage Example
 * 
 * ```kotlin
 * @Extension
 * class MyAgentPlugin : AgentPlugin {
 *     override fun getPluginId(): String = "my-agent-plugin"
 *     
 *     override fun getAgents(): List<Agent> = listOf(
 *         Agent(
 *             id = "code-reviewer",
 *             name = "Code Reviewer Agent",
 *             description = "Reviews code for best practices",
 *             version = "1.0.0",
 *             capabilities = listOf("code-review", "kotlin", "java"),
 *             requiredContext = setOf(ContextType.CODE_REVIEW),
 *             priority = 8
 *         )
 *     )
 * }
 * ```
 * 
 * ## Lifecycle
 * 
 * 1. Plugin is loaded by PF4J
 * 2. `initialize()` is called (optional override)
 * 3. `getAgents()` is called to register agents
 * 4. `destroy()` is called on shutdown (optional override)
 * 
 * ## Publishing Your Plugin
 * 
 * 1. Add dependency:
 * ```gradle
 * dependencies {
 *     compileOnly("io.whozoss.agentos:plugin-api:1.0.0")
 * }
 * ```
 * 
 * 2. Build plugin JAR with all dependencies
 * 3. Copy JAR to AgentOS `plugins/` directory
 * 4. AgentOS will auto-discover and load your plugin
 * 
 * @see Agent
 * @see ContextType
 * @see AgentStatus
 */
interface AgentPlugin : ExtensionPoint {
    
    /**
     * Unique identifier for this plugin.
     * 
     * Should be kebab-case and descriptive, e.g., "my-company-agents"
     * 
     * @return Plugin identifier
     */
    fun getPluginId(): String
    
    /**
     * List of agents provided by this plugin.
     * 
     * Called during AgentOS startup to register all agents.
     * Can return empty list if no agents are available.
     * 
     * @return List of agents (can be empty)
     */
    fun getAgents(): List<Agent>
    
    /**
     * Plugin version.
     * 
     * Should follow semantic versioning (MAJOR.MINOR.PATCH)
     * 
     * @return Version string (default: "1.0.0")
     */
    fun getVersion(): String = "1.0.0"
    
    /**
     * Human-readable plugin description.
     * 
     * @return Description (default: empty string)
     */
    fun getDescription(): String = ""
    
    /**
     * Initialize the plugin.
     * 
     * Called once after the plugin is loaded.
     * Use this to set up resources, connections, etc.
     * 
     * Optional - default implementation does nothing.
     */
    fun initialize() {}
    
    /**
     * Cleanup plugin resources.
     * 
     * Called once before the plugin is unloaded.
     * Use this to close connections, release resources, etc.
     * 
     * Optional - default implementation does nothing.
     */
    fun destroy() {}
}
