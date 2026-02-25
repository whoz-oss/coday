package io.whozoss.agentos.tool

import io.whozoss.agentos.sdk.tool.StandardTool
import io.whozoss.agentos.sdk.tool.ToolPlugin
import io.whozoss.agentos.tool.ToolRegistry
import jakarta.annotation.PostConstruct
import mu.KLogging
import org.pf4j.PluginManager
import org.springframework.stereotype.Service
import java.util.concurrent.ConcurrentHashMap

/**
 * Central registry for tool discovery and management.
 * Loads tools from plugins using PF4J extension system.
 *
 * This implementation is thread-safe using ConcurrentHashMap for concurrent access
 * by multiple agents and HTTP requests.
 */
@Service
class ToolRegistryService(
    private val pluginManager: PluginManager,
) : ToolRegistry {
    private val tools = ConcurrentHashMap<String, StandardTool<*>>()

    @PostConstruct
    fun initialize() {
        logger.info { "Initializing Tool Registry" }

        // Load tools from plugins
        loadToolsFromPlugins()

        logger.info { "Tool Registry initialized with ${tools.size} tool(s)" }
    }

    /**
     * Discover and load tools from all loaded plugins.
     */
    private fun loadToolsFromPlugins() {
        logger.info { "Searching for ToolPlugin extensions..." }

        val toolPlugins = pluginManager.getExtensions(ToolPlugin::class.java)

        logger.info { "Found ${toolPlugins.size} ToolPlugin extension(s)" }

        if (toolPlugins.isEmpty()) {
            logger.warn { "No ToolPlugin extensions found." }
            return
        }

        toolPlugins.forEach { toolPlugin ->
            val pluginWrapper = pluginManager.whichPlugin(toolPlugin::class.java)
            val pluginId =
                pluginWrapper?.pluginId ?: run {
                    logger.warn { "Could not determine plugin ID for ToolPlugin: ${toolPlugin::class.simpleName}" }
                    "unknown"
                }

            logger.info { "Loading tools from plugin: $pluginId" }

            try {
                val providedTools = toolPlugin.provideTools()

                providedTools.forEach { tool ->
                    registerTool(tool, source = "plugin:$pluginId")
                }

                logger.info { "Loaded ${providedTools.size} tool(s) from plugin: $pluginId" }
            } catch (e: Exception) {
                logger.error(e) { "Error loading tools from plugin $pluginId: ${e.message}" }
            }
        }
    }

    override fun registerTool(
        tool: StandardTool<*>,
        source: String,
    ) {
        val name = tool.name

        // Check for existing tool and clean up if needed
        if (tools.containsKey(name)) {
            // Clean up old tool if it implements AutoCloseable
            val oldTool = tools[name]
            if (oldTool is AutoCloseable) {
                try {
                    oldTool.close()
                    logger.debug { "Closed old tool instance: $name" }
                } catch (e: Exception) {
                    logger.error(e) { "Error closing old tool $name: ${e.message}" }
                }
            }
        }

        tools[name] = tool

        logger.info { "Registered tool: $name v${tool.version} from $source" }

        // TODO(WZ-28275): Emit tool_registered event when event system is integrated
    }

    override fun findTool(name: String): StandardTool<*>? = tools[name]

    override fun hasTool(name: String): Boolean = tools.containsKey(name)

    override fun unregisterTool(name: String): Boolean {
        val removed = tools.remove(name) != null
        if (removed) {
            logger.info { "Unregistered tool: $name" }
        }

        return removed
    }

    override fun listTools(): Collection<StandardTool<*>> = tools.values

    companion object : KLogging()
}
