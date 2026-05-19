package io.whozoss.agentos.tool

import io.whozoss.agentos.integrationConfig.IntegrationTypeRegistry
import io.whozoss.agentos.sdk.tool.ToolPlugin
import jakarta.annotation.PostConstruct
import mu.KLogging
import org.pf4j.PluginManager
import org.springframework.stereotype.Service
import java.util.concurrent.ConcurrentHashMap

/**
 * Central registry for tool plugin discovery and registration.
 *
 * At startup ([initialize]):
 * 1. Discovers all [ToolPlugin] extensions via PF4J.
 * 2. Registers an [IntegrationTypeDescriptor][io.whozoss.agentos.integrationConfig.IntegrationTypeDescriptor]
 *    for each plugin that declares a non-null [ToolPlugin.configSchema].
 * 3. Indexes all plugins by [ToolPlugin.integrationType] for fast lookup at resolution time.
 *
 * Tool resolution (namespace-scoped and user-scoped) is handled by [ToolResolverService].
 */
@Service
class ToolRegistryService(
    private val pluginManager: PluginManager,
    private val integrationTypeRegistry: IntegrationTypeRegistry,
    /**
     * Spring-managed [ToolPlugin] beans (internal integrations such as REDIRECT).
     * Registered alongside PF4J-loaded plugins; Spring injects all implementations
     * automatically via the list constructor parameter.
     */
    private val springToolPlugins: List<ToolPlugin> = emptyList(),
) {
    private val pluginsByType = ConcurrentHashMap<String, ToolPlugin>()

    fun findPlugin(integrationType: String): ToolPlugin? = pluginsByType[integrationType]

    fun findConfigLessPlugins(): List<ToolPlugin> = pluginsByType.values.filter { it.configSchema == null }

    @PostConstruct
    fun initialize() {
        logger.info { "Initializing Tool Registry" }
        loadPlugins()
        logger.info { "Tool Registry initialized with ${pluginsByType.size} plugin(s)" }
    }

    private fun loadPlugins() {
        val pf4jPlugins = pluginManager.getExtensions(ToolPlugin::class.java)
        logger.info { "Found ${pf4jPlugins.size} PF4J ToolPlugin extension(s) and ${springToolPlugins.size} Spring ToolPlugin bean(s)" }

        // Spring-managed internal plugins are registered first so PF4J plugins can
        // override them by type if needed (last-write-wins in pluginsByType).
        springToolPlugins.forEach { toolPlugin ->
            logger.info { "Registering Spring plugin: ${toolPlugin::class.simpleName} (integrationType='${toolPlugin.integrationType}')" }
            try {
                integrationTypeRegistry.registerFromPlugin(toolPlugin)
                pluginsByType[toolPlugin.integrationType] = toolPlugin
            } catch (e: Exception) {
                logger.error(e) { "Error loading Spring plugin ${toolPlugin::class.simpleName}: ${e.message}" }
            }
        }

        if (pf4jPlugins.isEmpty() && springToolPlugins.isEmpty()) {
            logger.warn { "No ToolPlugin extensions found." }
            return
        }

        pf4jPlugins.forEach { toolPlugin ->
            val pluginWrapper = pluginManager.whichPlugin(toolPlugin::class.java)
            val pluginId = pluginWrapper?.pluginId ?: run {
                logger.warn { "Could not determine plugin ID for ToolPlugin: ${toolPlugin::class.simpleName}" }
                "unknown"
            }

            logger.info { "Registering PF4J plugin: $pluginId (integrationType='${toolPlugin.integrationType}')" }

            try {
                integrationTypeRegistry.registerFromPlugin(toolPlugin)
                pluginsByType[toolPlugin.integrationType] = toolPlugin

                if (toolPlugin.configSchema == null) {
                    logger.info { "Plugin '$pluginId' requires no config — will be instantiated per agent run" }
                } else {
                    logger.info { "Plugin '$pluginId' requires config — tools will be resolved per namespace" }
                }
            } catch (e: Exception) {
                logger.error(e) { "Error loading PF4J plugin $pluginId: ${e.message}" }
            }
        }
    }

    companion object : KLogging()
}
