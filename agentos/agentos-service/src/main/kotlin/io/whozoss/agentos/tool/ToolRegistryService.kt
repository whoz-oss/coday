package io.whozoss.agentos.tool

import io.whozoss.agentos.integrationConfig.IntegrationConfigService
import io.whozoss.agentos.integrationConfig.IntegrationTypeRegistry
import io.whozoss.agentos.sdk.tool.StandardTool
import io.whozoss.agentos.sdk.tool.ToolPlugin
import jakarta.annotation.PostConstruct
import mu.KLogging
import org.pf4j.PluginManager
import org.springframework.stereotype.Service
import java.util.UUID
import java.util.concurrent.ConcurrentHashMap

/**
 * Central registry for tool discovery and namespace-scoped tool resolution.
 *
 * At startup ([initialize]):
 * 1. Discovers all [ToolPlugin] extensions via PF4J.
 * 2. Registers an [IntegrationTypeDescriptor][io.whozoss.agentos.integrationConfig.IntegrationTypeDescriptor]
 *    for each plugin that declares a non-null [ToolPlugin.configSchema].
 * 3. For plugins that require **no** configuration ([ToolPlugin.configSchema] == null),
 *    instantiates the tools immediately and registers them in the global registry.
 *    These tools are available in every namespace without any explicit configuration.
 *
 * At agent instantiation time, [resolveToolsForNamespace] is called with the target
 * namespace to build the full tool set for that agent run:
 * - Config-less plugins are included unconditionally (already in the global registry).
 * - Config-requiring plugins are instantiated once per matching [IntegrationConfig]
 *   found in the namespace, so a namespace can have multiple instances of the same
 *   integration type (e.g. two JIRA configs → two sets of JIRA tools).
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

    /** All loaded ToolPlugin extensions, indexed by integrationType for fast lookup. */
    private val pluginsByType = mutableMapOf<String, ToolPlugin>()

    @PostConstruct
    fun initialize() {
        logger.info { "Initializing Tool Registry" }
        loadPlugins()
        logger.info { "Tool Registry initialized with ${tools.size} config-less tool(s)" }
    }

    private fun loadPlugins() {
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

            logger.info { "Registering plugin: $pluginId (integrationType='${toolPlugin.integrationType}')" }

            try {
                // Register the IntegrationTypeDescriptor from the plugin's declared configSchema
                integrationTypeRegistry.registerFromPlugin(toolPlugin)

                // Index the plugin for namespace-scoped resolution
                pluginsByType[toolPlugin.integrationType] = toolPlugin

                // Plugins with no configSchema need no IntegrationConfig — instantiate
                // them immediately and register them in the global registry so they are
                // available in every namespace without explicit configuration.
                if (toolPlugin.configSchema == null) {
                    logger.info { "Plugin '$pluginId' requires no config — registering tools globally" }
                    val providedTools = toolPlugin.provideTools(null)
                    providedTools.forEach { registerTool(it, source = "plugin:$pluginId") }
                    logger.info { "Registered ${providedTools.size} config-less tool(s) from plugin: $pluginId" }
                } else {
                    logger.info { "Plugin '$pluginId' requires config — tools will be resolved per namespace" }
                }
            } catch (e: Exception) {
                logger.error(e) { "Error loading plugin $pluginId: ${e.message}" }
            }
        }
    }

    /**
     * Resolve the full tool set for a given namespace.
     *
     * Combines:
     * 1. Config-less tools already in the global registry (available everywhere).
     * 2. Tools instantiated from each [IntegrationConfig] found in the namespace,
     *    using the matching [ToolPlugin] as a factory.
     *
     * A namespace with no [IntegrationConfig] for a given plugin type simply gets
     * no tools from that plugin — silently.
     *
     * Called by [io.whozoss.agentos.agent.AgentServiceImpl] at agent instantiation time.
     */
    fun resolveToolsForNamespace(namespaceId: UUID): Collection<StandardTool<*>> {
        // Start with the config-less tools available in every namespace
        val resolved = tools.toMutableMap()

        // For each IntegrationConfig in the namespace, find the matching plugin and instantiate
        val configs = integrationConfigService.findByParent(namespaceId)
        logger.info { "[ToolRegistry] Resolving tools for namespace $namespaceId: ${configs.size} IntegrationConfig(s) found" }

        configs.forEach { config ->
            val plugin = pluginsByType[config.integrationType]
            if (plugin == null) {
                logger.warn { "[ToolRegistry] No plugin found for integrationType='${config.integrationType}' (config id=${config.metadata.id}) — skipping" }
                return@forEach
            }
            try {
                val providedTools = plugin.provideTools(config.parameters)
                providedTools.forEach { tool ->
                    if (resolved.containsKey(tool.name)) {
                        logger.warn { "[ToolRegistry] Tool name conflict: '${tool.name}' from integrationType='${config.integrationType}' overrides an existing entry" }
                    }
                    resolved[tool.name] = tool
                }
                logger.info { "[ToolRegistry] Resolved ${providedTools.size} tool(s) from integrationType='${config.integrationType}' for namespace $namespaceId" }
            } catch (e: Exception) {
                logger.error(e) { "[ToolRegistry] Error instantiating tools for integrationType='${config.integrationType}': ${e.message}" }
            }
        }

        logger.info { "[ToolRegistry] Total tools for namespace $namespaceId: ${resolved.size}" }
        return resolved.values
    }

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
