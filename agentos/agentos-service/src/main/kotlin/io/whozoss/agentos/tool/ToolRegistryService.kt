package io.whozoss.agentos.tool

import io.whozoss.agentos.integrationConfig.IntegrationConfig
import io.whozoss.agentos.integrationConfig.IntegrationConfigService
import io.whozoss.agentos.integrationConfig.IntegrationTypeRegistry
import io.whozoss.agentos.reconciliation.ConfigNotFoundException
import io.whozoss.agentos.reconciliation.ConfigMergeService
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
    private val integrationConfigMergeService: ConfigMergeService<IntegrationConfig>,
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
     * Resolve the full tool set for a given namespace + user run, applying 3-tier reconciliation
     * to each [IntegrationConfig] name discovered across namespace-shared and user overlay layers.
     *
     * Combines:
     * 1. Fresh instances from config-less plugins.
     * 2. For each distinct config name found in namespace-shared OR user overlays, a reconciled
     *    config is built via [ConfigMergeService] and passed to the matching plugin.
     *
     * When [agentIntegrations] is non-null, the same two-level filter as [resolveToolsForNamespace]
     * applies: integrations not listed are skipped, and tool-level filtering uses [isToolAllowed].
     *
     * Discriminated [ConfigNotFoundException] handling (review H-8 / NFR-REL-1 vs FR30):
     * - When the failing `name` comes EXCLUSIVELY from `findByUserId` (no matching
     *   `findByNamespaceShared`), it is a **dormant user override** — the user persisted an
     *   override that no longer matches any namespace-shared config. Swallow the exception,
     *   warn, and skip the name (FR30: "remains dormant without raising an error").
     * - When the failing `name` comes from `findByNamespaceShared` (the namespace itself
     *   declares the integration), reconciliation MUST succeed. **Fail-closed**: rethrow so
     *   the run aborts rather than silently producing a partial toolset (NFR-REL-1).
     *
     * [resolveToolsForNamespace] is preserved unchanged for legacy callers without userId (AC1).
     */
    fun resolveToolsForRun(
        namespaceId: UUID,
        userId: UUID,
        cache: RunReconciliationCache? = null,
        agentIntegrations: Map<String, List<String>?>? = null,
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
        val sharedNames = sharedConfigs.map { it.name }.toSet()
        val distinctNames = sharedNames + userOverrides.map { it.name }

        logger.info {
            "[ToolRegistry] resolveToolsForRun namespace=$namespaceId user=$userId: " +
                "${sharedConfigs.size} shared + ${userOverrides.size} user overrides → ${distinctNames.size} distinct names"
        }

        // Surface integration names declared by the agent that resolve to nothing — would
        // otherwise produce an empty toolset for that name with no runtime trace.
        if (agentIntegrations != null) {
            val orphans = agentIntegrations.keys - distinctNames
            if (orphans.isNotEmpty()) {
                logger.warn {
                    "[ToolRegistry] Agent declares integration(s) ${orphans.toSortedSet()} but no matching " +
                        "IntegrationConfig found in namespace $namespaceId for user $userId — these tools will be empty."
                }
            }
        }

        // Reconcile each name and instantiate via plugin
        distinctNames.forEach { name ->
            // Apply agent integrations filter at the integration level
            if (agentIntegrations != null && name !in agentIntegrations) {
                logger.debug { "[ToolRegistry] Skipping IntegrationConfig '$name' (not in agent integrations filter)" }
                return@forEach
            }

            val resolvedConfig = try {
                cache?.getOrCompute(name, IntegrationConfig::class.java, namespaceId, userId) {
                    integrationConfigMergeService.resolve(namespaceId, userId, name)
                } ?: integrationConfigMergeService.resolve(namespaceId, userId, name)
            } catch (e: ConfigNotFoundException) {
                if (name in sharedNames) {
                    // The namespace itself declares this integration → reconciliation MUST
                    // succeed. Fail-closed per NFR-REL-1 (no silent partial toolset).
                    logger.error { "[ToolRegistry] Reconciliation failed for namespace-shared name='$name' — failing the run" }
                    throw e
                }
                // Dormant user override (FR30): user persisted an override targeting a name
                // that no longer matches any shared config. Skip silently.
                logger.warn { "[ToolRegistry] Dormant user override for name='$name' (no matching shared config): ${e.message}" }
                return@forEach
            }

            val plugin = pluginsByType[resolvedConfig.integrationType] ?: run {
                logger.warn { "[ToolRegistry] No plugin for integrationType='${resolvedConfig.integrationType}' (name='$name')" }
                return@forEach
            }

            try {
                val allowedToolNames = agentIntegrations?.get(resolvedConfig.name)
                val tools = plugin.provideTools(resolvedConfig.parameters, resolvedConfig.name)
                    .filter { tool -> isToolAllowed(tool.name, resolvedConfig.name, allowedToolNames) }
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
