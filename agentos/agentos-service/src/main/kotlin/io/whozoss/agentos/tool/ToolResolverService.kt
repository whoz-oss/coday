package io.whozoss.agentos.tool

import io.whozoss.agentos.integrationConfig.IntegrationConfig
import io.whozoss.agentos.integrationConfig.IntegrationConfigService
import io.whozoss.agentos.exception.ConfigNotFoundException
import io.whozoss.agentos.reconciliation.ConfigMergeService
import io.whozoss.agentos.reconciliation.RunReconciliationCache
import io.whozoss.agentos.sdk.tool.StandardTool
import mu.KLogging
import org.springframework.stereotype.Service
import java.util.UUID

@Service
class ToolResolverService(
    private val toolRegistryService: ToolRegistryService,
    private val integrationConfigService: IntegrationConfigService,
    private val integrationConfigMergeService: ConfigMergeService<IntegrationConfig>,
) {

    fun resolveToolsForNamespace(
        namespaceId: UUID,
        agentIntegrations: Map<String, List<String>?>? = null,
    ): Collection<StandardTool<*>> {
        val resolved = mutableMapOf<String, StandardTool<*>>()
        val pluginsByType = toolRegistryService.getPluginsByType()

        val configs = integrationConfigService.findByParent(namespaceId)
        logger.info { "[ToolResolver] Resolving tools for namespace $namespaceId: ${configs.size} IntegrationConfig(s) found" }

        configs.forEach { config ->
            if (agentIntegrations != null && config.name !in agentIntegrations) {
                logger.debug { "[ToolResolver] Skipping IntegrationConfig '${config.name}' (not in agent integrations filter)" }
                return@forEach
            }
            val plugin = pluginsByType[config.integrationType]
            if (plugin == null) {
                logger.warn { "[ToolResolver] No plugin found for integrationType='${config.integrationType}' (config id=${config.metadata.id}) — skipping" }
                return@forEach
            }
            try {
                val allowedToolNames = agentIntegrations?.get(config.name)
                val providedTools = plugin.provideTools(config.parameters, config.name)
                    .filter { tool -> isToolAllowed(tool.name, config.name, allowedToolNames) }
                providedTools.forEach { tool ->
                    if (resolved.containsKey(tool.name)) {
                        logger.warn { "[ToolResolver] Tool name conflict: '${tool.name}' from integrationType='${config.integrationType}' overrides an existing entry" }
                    }
                    resolved[tool.name] = tool
                }
                logger.info { "[ToolResolver] Resolved ${providedTools.size} tool(s) from integrationType='${config.integrationType}' for namespace $namespaceId" }
            } catch (e: Exception) {
                logger.error(e) { "[ToolResolver] Error instantiating tools for integrationType='${config.integrationType}': ${e.message}" }
            }
        }

        logger.info { "[ToolResolver] Total tools for namespace $namespaceId: ${resolved.size}" }
        return resolved.values
    }

    fun resolveToolsForRun(
        namespaceId: UUID,
        userId: UUID,
        cache: RunReconciliationCache? = null,
        agentIntegrations: Map<String, List<String>?>? = null,
    ): Collection<StandardTool<*>> {
        val resolved = mutableMapOf<String, StandardTool<*>>()
        val pluginsByType = toolRegistryService.getPluginsByType()

        pluginsByType.values
            .filter { it.configSchema == null }
            .forEach { plugin ->
                try {
                    val tools = plugin.provideTools(null)
                    tools.forEach { tool ->
                        if (resolved.containsKey(tool.name)) {
                            logger.warn { "[ToolResolver] Tool name conflict: '${tool.name}' from config-less plugin" }
                        }
                        resolved[tool.name] = tool
                    }
                } catch (e: Exception) {
                    logger.error(e) { "[ToolResolver] Error instantiating config-less tools for plugin '${plugin.integrationType}': ${e.message}" }
                }
            }

        val sharedConfigs = integrationConfigService.findByNamespaceShared(namespaceId)
        val userOverrides = integrationConfigService.findByUserId(userId)
            .filter { it.namespaceId == null || it.namespaceId == namespaceId }
        val sharedNames = sharedConfigs.map { it.name }.toSet()
        val distinctNames = sharedNames + userOverrides.map { it.name }

        logger.info {
            "[ToolResolver] resolveToolsForRun namespace=$namespaceId user=$userId: " +
                "${sharedConfigs.size} shared + ${userOverrides.size} user overrides → ${distinctNames.size} distinct names"
        }

        if (agentIntegrations != null) {
            val orphans = agentIntegrations.keys - distinctNames
            if (orphans.isNotEmpty()) {
                logger.warn {
                    "[ToolResolver] Agent declares integration(s) ${orphans.toSortedSet()} but no matching " +
                        "IntegrationConfig found in namespace $namespaceId for user $userId — these tools will be empty."
                }
            }
        }

        distinctNames.forEach { name ->
            if (agentIntegrations != null && name !in agentIntegrations) {
                logger.debug { "[ToolResolver] Skipping IntegrationConfig '$name' (not in agent integrations filter)" }
                return@forEach
            }

            val resolvedConfig = try {
                cache?.getOrCompute(name, IntegrationConfig::class.java, namespaceId, userId) {
                    integrationConfigMergeService.resolve(namespaceId, userId, name)
                } ?: integrationConfigMergeService.resolve(namespaceId, userId, name)
            } catch (e: ConfigNotFoundException) {
                if (name in sharedNames) {
                    logger.error { "[ToolResolver] Reconciliation failed for namespace-shared name='$name' — failing the run" }
                    throw e
                }
                logger.warn { "[ToolResolver] Dormant user override for name='$name' (no matching shared config): ${e.message}" }
                return@forEach
            }

            val plugin = pluginsByType[resolvedConfig.integrationType] ?: run {
                logger.warn { "[ToolResolver] No plugin for integrationType='${resolvedConfig.integrationType}' (name='$name')" }
                return@forEach
            }

            try {
                val allowedToolNames = agentIntegrations?.get(resolvedConfig.name)
                val tools = plugin.provideTools(resolvedConfig.parameters, resolvedConfig.name)
                    .filter { tool -> isToolAllowed(tool.name, resolvedConfig.name, allowedToolNames) }
                tools.forEach { tool ->
                    if (resolved.containsKey(tool.name)) {
                        logger.warn { "[ToolResolver] Tool name conflict: '${tool.name}' from integrationType='${resolvedConfig.integrationType}'" }
                    }
                    resolved[tool.name] = tool
                }
                logger.info { "[ToolResolver] Resolved ${tools.size} tool(s) from name='$name' (type='${resolvedConfig.integrationType}')" }
            } catch (e: Exception) {
                logger.error(e) { "[ToolResolver] Error instantiating tools for name='$name': ${e.message}" }
            }
        }

        logger.info { "[ToolResolver] Total tools for namespace $namespaceId (user $userId): ${resolved.size}" }
        return resolved.values
    }

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
