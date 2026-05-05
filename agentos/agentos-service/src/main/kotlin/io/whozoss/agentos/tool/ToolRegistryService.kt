package io.whozoss.agentos.tool

import io.whozoss.agentos.integrationConfig.IntegrationConfig
import io.whozoss.agentos.integrationConfig.IntegrationConfigService
import io.whozoss.agentos.integrationConfig.IntegrationTypeRegistry
import io.whozoss.agentos.reconciliation.ConfigNotFoundException
import io.whozoss.agentos.reconciliation.ConfigReconciliationService
import io.whozoss.agentos.reconciliation.RunReconciliationCache
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
    private val integrationConfigReconciliationService: ConfigReconciliationService<IntegrationConfig>,
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
     * Resolve the full tool set for a given namespace and agent run.
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
     * Called by [io.whozoss.agentos.agent.AgentServiceImpl] at agent instantiation time.
     */
    fun resolveToolsForNamespace(namespaceId: UUID): Collection<StandardTool<*>> {
        val resolved = mutableMapOf<String, StandardTool<*>>()

        // Instantiate config-less tools fresh for this run
        pluginsByType.values
            .filter { it.configSchema == null }
            .forEach { plugin ->
                try {
                    val providedTools = plugin.provideTools(null)
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
            val plugin = pluginsByType[config.integrationType]
            if (plugin == null) {
                logger.warn { "[ToolRegistry] No plugin found for integrationType='${config.integrationType}' (config id=${config.metadata.id}) — skipping" }
                return@forEach
            }
            try {
                val providedTools = plugin.provideTools(config.parameters, config.name)
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
     * Resolve the full tool set for a given namespace + user run, applying 3-tier reconciliation
     * to each [IntegrationConfig] name discovered across namespace-shared and user overlay layers.
     *
     * Combines:
     * 1. Fresh instances from config-less plugins.
     * 2. For each distinct config name found in namespace-shared OR user overlays, a reconciled
     *    config is built via [ConfigReconciliationService] and passed to the matching plugin.
     *
     * A [ConfigNotFoundException] for a given name is swallowed (warning logged, name skipped).
     * This preserves the posture "a dormant override must not break the run" (FR30).
     *
     * [resolveToolsForNamespace] is preserved unchanged for legacy callers without userId (AC1).
     */
    fun resolveToolsForRun(
        namespaceId: UUID,
        userId: UUID,
        cache: RunReconciliationCache? = null,
    ): Collection<StandardTool<*>> {
        val resolved = mutableMapOf<String, StandardTool<*>>()

        // Config-less plugins: fresh instances per run
        pluginsByType.values
            .filter { it.configSchema == null }
            .forEach { plugin ->
                try {
                    val tools = plugin.provideTools(null)
                    tools.forEach { tool ->
                        if (resolved.containsKey(tool.name)) {
                            logger.warn { "[ToolRegistry] Tool name conflict: '${tool.name}' from config-less plugin" }
                        }
                        resolved[tool.name] = tool
                    }
                } catch (e: Exception) {
                    logger.error(e) { "[ToolRegistry] Error instantiating config-less tools for plugin '${plugin.integrationType}': ${e.message}" }
                }
            }

        // Enumerate all distinct names across the 3 sources
        val sharedConfigs = integrationConfigService.findByNamespaceShared(namespaceId)
        val userOverrides = integrationConfigService.findByUserId(userId)
            .filter { it.namespaceId == null || it.namespaceId == namespaceId }
        val distinctNames = (sharedConfigs.map { it.name } + userOverrides.map { it.name }).toSet()

        logger.info {
            "[ToolRegistry] resolveToolsForRun namespace=$namespaceId user=$userId: " +
                "${sharedConfigs.size} shared + ${userOverrides.size} user overrides → ${distinctNames.size} distinct names"
        }

        // Reconcile each name and instantiate via plugin
        distinctNames.forEach { name ->
            val resolvedConfig = try {
                cache?.getOrCompute(name, IntegrationConfig::class.java) {
                    integrationConfigReconciliationService.resolve(namespaceId, userId, name)
                } ?: integrationConfigReconciliationService.resolve(namespaceId, userId, name)
            } catch (e: ConfigNotFoundException) {
                logger.warn { "[ToolRegistry] Reconciliation failed for name='$name': ${e.message}" }
                return@forEach
            }

            val plugin = pluginsByType[resolvedConfig.integrationType] ?: run {
                logger.warn { "[ToolRegistry] No plugin for integrationType='${resolvedConfig.integrationType}' (name='$name')" }
                return@forEach
            }

            try {
                val tools = plugin.provideTools(resolvedConfig.parameters, resolvedConfig.name)
                tools.forEach { tool ->
                    if (resolved.containsKey(tool.name)) {
                        logger.warn { "[ToolRegistry] Tool name conflict: '${tool.name}' from integrationType='${resolvedConfig.integrationType}'" }
                    }
                    resolved[tool.name] = tool
                }
                logger.info { "[ToolRegistry] Resolved ${tools.size} tool(s) from name='$name' (type='${resolvedConfig.integrationType}')" }
            } catch (e: Exception) {
                logger.error(e) { "[ToolRegistry] Error instantiating tools for name='$name': ${e.message}" }
            }
        }

        logger.info { "[ToolRegistry] Total tools for namespace $namespaceId (user $userId): ${resolved.size}" }
        return resolved.values
    }

    companion object : KLogging()
}
