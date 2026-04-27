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
 * 3. Indexes all plugins by [ToolPlugin.integrationType] for fast lookup at resolution time.
 *
 * At agent run time, [resolveToolsForNamespace] is called to build the tool set for
 * that specific agent run. It produces **fresh instances** for every call:
 * - Config-less plugins ([ToolPlugin.configSchema] == null) are instantiated on every call
 *   so each agent run owns its own tool instances, preventing cross-run state sharing.
 * - Config-requiring plugins are instantiated once per matching [IntegrationConfig] found
 *   in the namespace, so a namespace can have multiple instances of the same integration
 *   type (e.g. two JIRA configs → two sets of JIRA tools).
 *
 * Tool instances therefore live exactly as long as the agent run that created them —
 * no tool instance outlives its owning agent run.
 *
 * This implementation is thread-safe: [ConcurrentHashMap] handles concurrent reads from
 * multiple agents.
 */
@Service
class ToolRegistryService(
    private val pluginManager: PluginManager,
    private val integrationConfigService: IntegrationConfigService,
    private val integrationTypeRegistry: IntegrationTypeRegistry,
) {
    /** All loaded ToolPlugin extensions, indexed by integrationType for fast lookup. */
    private val pluginsByType = ConcurrentHashMap<String, ToolPlugin>()

    @PostConstruct
    fun initialize() {
        logger.info { "Initializing Tool Registry" }
        loadPlugins()
        logger.info { "Tool Registry initialized with ${pluginsByType.size} plugin(s)" }
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

                if (toolPlugin.configSchema == null) {
                    logger.info { "Plugin '$pluginId' requires no config — will be instantiated per agent run" }
                } else {
                    logger.info { "Plugin '$pluginId' requires config — tools will be resolved per namespace" }
                }
            } catch (e: Exception) {
                logger.error(e) { "Error loading plugin $pluginId: ${e.message}" }
            }
        }
    }

    /**
     * Resolve the tool set for a given namespace and agent run.
     *
     * Every call produces **new tool instances** — tools are scoped to the agent run
     * and discarded when the run ends. No tool instance is shared across runs.
     *
     * Combines:
     * 1. Fresh instances from config-less plugins (available in every namespace).
     * 2. Fresh instances from each [IntegrationConfig] found in the namespace,
     *    using the matching [ToolPlugin] as a factory.
     *
     * A namespace with no [IntegrationConfig] for a given plugin type simply gets
     * no tools from that plugin — silently.
     *
     * When [agentIntegrations] is non-null, only tools belonging to the listed
     * integrations are included. The filter is two-level:
     * - Integration-level: a tool is included only if its integration key appears
     *   in [agentIntegrations]. For config-less tools the key is
     *   [ToolPlugin.integrationType]; for config-backed tools it is
     *   [IntegrationConfig.name] (the multi-instance prefix, e.g. `JIRA_PROD`).
     * - Tool-level: when the list for a key is non-null, only tools whose [StandardTool.name]
     *   ends with the allowed suffix are included (exact match or `KEY__suffix` match).
     *
     * When [agentIntegrations] is null, all tools are returned (no filtering).
     *
     * Called by [io.whozoss.agentos.agent.AgentServiceImpl] at agent instantiation time.
     */
    fun resolveToolsForNamespace(
        namespaceId: UUID,
        agentIntegrations: Map<String, List<String>?>? = null,
    ): Collection<StandardTool<*>> {
        val resolved = mutableMapOf<String, StandardTool<*>>()

        // Instantiate config-less tools fresh for this run
        pluginsByType.values
            .filter { it.configSchema == null }
            .forEach { plugin ->
                // Skip this integration entirely if the agent has an integrations filter
                // and this integrationType is not listed.
                if (agentIntegrations != null && plugin.integrationType !in agentIntegrations) {
                    logger.debug { "[ToolRegistry] Skipping config-less plugin '${plugin.integrationType}' (not in agent integrations filter)" }
                    return@forEach
                }
                try {
                    val allowedToolNames = agentIntegrations?.get(plugin.integrationType)
                    val providedTools = plugin.provideTools(null)
                        .filter { tool -> isToolAllowed(tool.name, plugin.integrationType, allowedToolNames) }
                    providedTools.forEach { tool ->
                        if (resolved.containsKey(tool.name)) {
                            logger.warn { "[ToolRegistry] Tool name conflict: '${tool.name}' from config-less plugin overrides an existing entry" }
                        }
                        resolved[tool.name] = tool
                    }
                    logger.debug { "[ToolRegistry] Instantiated ${providedTools.size} config-less tool(s) from plugin '${plugin.integrationType}' for run in namespace $namespaceId" }
                } catch (e: Exception) {
                    logger.error(e) { "[ToolRegistry] Error instantiating config-less tools for plugin '${plugin.integrationType}': ${e.message}" }
                }
            }

        // For each IntegrationConfig in the namespace, find the matching plugin and instantiate
        val configs = integrationConfigService.findByParent(namespaceId)
        logger.info { "[ToolRegistry] Resolving tools for namespace $namespaceId: ${configs.size} IntegrationConfig(s) found" }

        configs.forEach { config ->
            // Skip this integration config entirely if the agent has an integrations filter
            // and this config name is not listed.
            if (agentIntegrations != null && config.name !in agentIntegrations) {
                logger.debug { "[ToolRegistry] Skipping IntegrationConfig '${config.name}' (not in agent integrations filter)" }
                return@forEach
            }
            val plugin = pluginsByType[config.integrationType]
            if (plugin == null) {
                logger.warn { "[ToolRegistry] No plugin found for integrationType='${config.integrationType}' (config id=${config.metadata.id}) — skipping" }
                return@forEach
            }
            try {
                val allowedToolNames = agentIntegrations?.get(config.name)
                val providedTools = plugin.provideTools(config.parameters, config.name)
                    .filter { tool -> isToolAllowed(tool.name, config.name, allowedToolNames) }
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

    /**
     * Determines whether a tool is allowed given the integration key and the
     * optional list of allowed tool names.
     *
     * [allowedNames] null means all tools from this integration are allowed.
     * When non-null, the tool name must either match exactly one of the allowed
     * names, or end with `__<allowedName>` (the multi-instance prefix convention).
     */
    internal fun isToolAllowed(
        toolName: String,
        integrationKey: String,
        allowedNames: List<String>?,
    ): Boolean {
        if (allowedNames == null) return true
        return allowedNames.any { allowed ->
            toolName == allowed || toolName == "${integrationKey}__${allowed}"
        }
    }

    companion object : KLogging()
}
