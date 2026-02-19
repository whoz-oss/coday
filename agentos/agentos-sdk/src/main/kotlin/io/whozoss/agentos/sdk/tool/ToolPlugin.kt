package io.whozoss.agentos.sdk.tool

import org.pf4j.ExtensionPoint

/**
 * Extension point for plugins that provide tools.
 *
 * Plugins implement this interface to contribute tools to the AgentOS tool registry.
 * The AgentOS service will discover all implementations of this interface
 * using PF4J's extension mechanism and automatically register their tools.
 *
 * A plugin consists of two classes:
 * 1. A Plugin class that extends org.pf4j.Plugin
 * 2. A ToolPlugin implementation annotated with @Extension
 *
 * Example usage:
 * ```kotlin
 * // 1. Plugin class
 * class MyPlugin(wrapper: PluginWrapper) : Plugin(wrapper) {
 *     override fun start() { logger.info("Plugin started") }
 *     override fun stop() { logger.info("Plugin stopped") }
 * }
 *
 * // 2. Tool provider with @Extension
 * @Extension
 * class MyToolProvider : ToolPlugin {
 *     override fun provideTools(): List<StandardTool<*>> {
 *         return listOf(
 *             MyCustomTool(),
 *             AnotherTool()
 *         )
 *     }
 * }
 * ```
 */
interface ToolPlugin : ExtensionPoint {
    /**
     * Provide tools that this plugin contributes to the registry.
     * Called once when the plugin is loaded during AgentOS initialization.
     *
     * @return List of tool implementations to register
     */
    fun provideTools(): List<StandardTool<*>>
}
