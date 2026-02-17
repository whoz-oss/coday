package io.whozoss.agentos.sdk.tool

/**
 * Central registry for tool discovery and execution.
 * Implemented by the AgentOS service layer.
 *
 * This interface provides a standardized way for agents to:
 * - Discover available tools
 * - Query tool metadata
 * - Access tool implementations
 *
 * Implementations MUST be thread-safe as tools may be accessed concurrently
 * by multiple agents and HTTP requests.
 */
interface ToolRegistry {
    /**
     * Register a tool in the registry.
     *
     * @param tool The tool implementation to register
     * @param source The source of the tool (e.g., "system", "plugin:datetime-tools")
     * @return The tool's unique identifier
     */
    fun registerTool(
        tool: StandardTool<*>,
        source: String = "system",
    )

    /**
     * Find a tool by its exact name.
     *
     * @param name The exact name of the tool
     * @return The tool implementation, or null if not found
     */
    fun findTool(name: String): StandardTool<*>?

    /**
     * Check if a tool exists in the registry.
     *
     * @param name The exact name of the tool
     * @return true if the tool exists, false otherwise
     */
    fun hasTool(name: String): Boolean

    /**
     * Unregister a tool from the registry.
     * Useful for plugin cleanup or hot-reloading.
     *
     * @param name The exact name of the tool to unregister
     * @return true if the tool was unregistered, false if it didn't exist
     */
    fun unregisterTool(name: String): Boolean

    fun listTools(): Collection<StandardTool<*>>
}
