package io.whozoss.agentos.tool

import io.whozoss.agentos.integrationConfig.IntegrationConfigService
import io.whozoss.agentos.integrationConfig.IntegrationTypeRegistry
import io.whozoss.agentos.sdk.tool.StandardTool
import io.whozoss.agentos.sdk.tool.ToolContext
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
    /**
     * Spring-managed [ToolPlugin] beans (internal integrations such as REDIRECT).
     * Registered alongside PF4J-loaded plugins; Spring injects all implementations
     * automatically via the list constructor parameter.
     */
    private val springToolPlugins: List<ToolPlugin> = emptyList(),
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
                logger.error(e) { "Error loading PF4J plugin $pluginId: ${e.message}" }
            }
        }
    }

    /**
     * Resolve the tool set for a given namespace and agent run.
     *
     * Every call produces **new tool instances** — tools are scoped to the agent run
     * and discarded when the run ends. No tool instance is shared across runs.
     *
     * Tools are always resolved via the [IntegrationConfig] entries of the namespace:
     * each config is matched to a [ToolPlugin] by [ToolPlugin.integrationType], and
     * the plugin instantiates fresh tools from the config parameters. This applies to
     * both config-requiring plugins and config-less plugins — a config-less plugin only
     * contributes tools when an [IntegrationConfig] of its type exists in the namespace.
     *
     * A namespace with no [IntegrationConfig] for a given plugin type gets no tools
     * from that plugin — silently.
     *
     * When [agentIntegrations] is non-null, only tools whose [IntegrationConfig.name]
     * appears as a key in the map are included. The filter is two-level:
     * - Integration-level: the [IntegrationConfig.name] must be a key in [agentIntegrations].
     * - Tool-level: when the list for that key is non-null, only tools whose [StandardTool.name]
     *   matches exactly or ends with `KEY__allowedName` are included.
     *
     * When [agentIntegrations] is null, all tools from all namespace configs are returned.
     *
     * Called by [io.whozoss.agentos.agent.AgentServiceImpl] at agent instantiation time.
     */
    fun resolveToolsForNamespace(
        namespaceId: UUID,
        agentIntegrations: Map<String, List<String>?>? = null,
    ): Collection<StandardTool<*>> {
        val resolved = mutableMapOf<String, StandardTool<*>>()

        // All tools are resolved via IntegrationConfig — config-less plugins included.
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
                val toolContext = ToolContext(
                    namespaceId = namespaceId,
                    userId = null,
                    userExternalId = null,
                    caseEvents = emptyList(),
                )
                val providedTools = plugin.provideTools(config.parameters, config.name, toolContext)
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
