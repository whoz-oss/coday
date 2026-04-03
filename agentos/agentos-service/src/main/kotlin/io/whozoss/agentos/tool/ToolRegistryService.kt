package io.whozoss.agentos.tool

import io.whozoss.agentos.integrationConfig.IntegrationConfigService
import io.whozoss.agentos.integrationConfig.IntegrationTypeRegistry
import io.whozoss.agentos.sdk.tool.StandardTool
import io.whozoss.agentos.sdk.tool.ToolPlugin
import jakarta.annotation.PostConstruct
import mu.KLogging
import org.pf4j.PluginManager
import org.springframework.stereotype.Service
import java.util.concurrent.ConcurrentHashMap

/**
 * Central registry for tool discovery and management.
 *
 * At startup ([initialize]):
 * 1. Discovers all [ToolPlugin] extensions via PF4J.
 * 2. For each plugin, looks up a persisted [IntegrationConfig][io.whozoss.agentos.integrationConfig.IntegrationConfig]
 *    matching [ToolPlugin.integrationType] (currently namespace-unscoped — first config found wins).
 * 3. Calls [ToolPlugin.provideTools] with the resolved config (or null if none exists).
 * 4. Registers the resulting tools in the in-memory map.
 * 5. Registers an [IntegrationTypeDescriptor][io.whozoss.agentos.integrationConfig.IntegrationTypeDescriptor]
 *    for each plugin that declares a non-null [ToolPlugin.configSchema], so clients can
 *    discover what configuration each integration type expects.
 *
 * Note on namespace scoping: [IntegrationConfig] entities are namespace-scoped, but the
 * tool registry is currently global. Until the registry becomes namespace-aware, config
 * lookup is best-effort: the first config found for the matching [integrationType] is used.
 * Tools that receive no config fall back to their built-in defaults.
 *
 * This implementation is thread-safe: [ConcurrentHashMap] handles concurrent reads from
 * multiple agents and HTTP requests.
 */
@Service
class ToolRegistryService(
    private val pluginManager: PluginManager,
    private val integrationConfigService: IntegrationConfigService,
    private val integrationTypeRegistry: IntegrationTypeRegistry,
) : ToolRegistry {
    private val tools = ConcurrentHashMap<String, StandardTool<*>>()

    @PostConstruct
    fun initialize() {
        logger.info { "Initializing Tool Registry" }
        loadToolsFromPlugins()
        logger.info { "Tool Registry initialized with ${tools.size} tool(s)" }
    }

    private fun loadToolsFromPlugins() {
        val toolPlugins = pluginManager.getExtensions(ToolPlugin::class.java)
        logger.info { "Found ${toolPlugins.size} ToolPlugin extension(s)" }

        if (toolPlugins.isEmpty()) {
            logger.warn { "No ToolPlugin extensions found." }
            return
        }

        toolPlugins.forEach { toolPlugin ->
            val pluginWrapper = pluginManager.whichPlugin(toolPlugin::class.java)
            val pluginId = pluginWrapper?.pluginId ?: run {
                logger.warn { "Could not determine plugin ID for ToolPlugin: ${toolPlugin::class.simpleName}" }
                "unknown"
            }

            logger.info { "Loading tools from plugin: $pluginId (integrationType='${toolPlugin.integrationType}')" }

            try {
                // Register the IntegrationTypeDescriptor from the plugin's declared configSchema
                integrationTypeRegistry.registerFromPlugin(toolPlugin)

                // Resolve config: find any persisted IntegrationConfig for this integrationType.
                // Best-effort — namespace-unscoped until the registry becomes namespace-aware.
                val config = resolveConfig(toolPlugin.integrationType)
                if (config != null) {
                    logger.info { "Found persisted config for integrationType='${toolPlugin.integrationType}'" }
                } else {
                    logger.info { "No persisted config for integrationType='${toolPlugin.integrationType}' — using plugin defaults" }
                }

                val providedTools = toolPlugin.provideTools(config)
                providedTools.forEach { registerTool(it, source = "plugin:$pluginId") }
                logger.info { "Loaded ${providedTools.size} tool(s) from plugin: $pluginId" }
            } catch (e: Exception) {
                logger.error(e) { "Error loading tools from plugin $pluginId: ${e.message}" }
            }
        }
    }

    /**
     * Resolve the configuration for a given [integrationType] across all namespaces.
     *
     * Until the tool registry is namespace-scoped, this performs a best-effort lookup:
     * it iterates all persisted [IntegrationConfig]s and returns the parameters of the
     * first one whose [integrationType] matches.
     *
     * Returns null if no matching config is found.
     */
    private fun resolveConfig(integrationType: String) =
        integrationConfigService
            .findAll()
            .firstOrNull { it.integrationType == integrationType }
            ?.parameters

    override fun registerTool(tool: StandardTool<*>, source: String) {
        val name = tool.name
        val existing = tools[name]
        if (existing is AutoCloseable) {
            try {
                existing.close()
                logger.debug { "Closed old tool instance: $name" }
            } catch (e: Exception) {
                logger.error(e) { "Error closing old tool $name: ${e.message}" }
            }
        }
        tools[name] = tool
        logger.info { "Registered tool: $name v${tool.version} from $source" }
    }

    override fun findTool(name: String): StandardTool<*>? = tools[name]

    override fun hasTool(name: String): Boolean = tools.containsKey(name)

    override fun unregisterTool(name: String): Boolean {
        val removed = tools.remove(name) != null
        if (removed) logger.info { "Unregistered tool: $name" }
        return removed
    }

    override fun listTools(): Collection<StandardTool<*>> = tools.values

    companion object : KLogging()
}
