package io.whozoss.agentos.tool

import io.whozoss.agentos.exception.ConfigNotFoundException
import io.whozoss.agentos.integrationConfig.IntegrationConfig
import io.whozoss.agentos.integrationConfig.IntegrationConfigService
import io.whozoss.agentos.reconciliation.ConfigMergeService
import io.whozoss.agentos.sdk.tool.StandardTool
import io.whozoss.agentos.sdk.tool.ToolContext
import io.whozoss.agentos.sdk.tool.ToolPlugin
import mu.KLogging
import org.springframework.stereotype.Service
import java.util.UUID

@Service
class ToolResolverService(
    private val toolRegistryService: ToolRegistryService,
    private val integrationConfigService: IntegrationConfigService,
    private val integrationConfigMergeService: ConfigMergeService<IntegrationConfig>,
) {
    /**
     * Resolves the tool set for a namespace-level agent run (no authenticated user).
     *
     * @param namespaceId The namespace to resolve tools for.
     * @param agentIntegrations Optional integration filter from [AgentConfig.integrations].
     *   When null, all namespace integrations are included.
     * @param agentName The name of the agent being built, or null when resolving outside
     *   an agent context. Passed into [ToolContext] so plugins can exclude the calling
     *   agent from lists they build (e.g. the redirect tool excludes itself as a target).
     */
    fun resolveToolsForNamespace(
        namespaceId: UUID,
        agentIntegrations: Map<String, List<String>?>? = null,
        agentName: String? = null,
    ): Collection<StandardTool<*>> {
        val resolved = mutableMapOf<String, StandardTool<*>>()

        val configs = integrationConfigService.findByParent(namespaceId)
        logger.info { "[ToolResolver] Resolving tools for namespace $namespaceId: ${configs.size} IntegrationConfig(s) found" }

        configs.forEach { config ->
            if (agentIntegrations != null && config.name !in agentIntegrations) {
                logger.debug { "[ToolResolver] Skipping IntegrationConfig '${config.name}' (not in agent integrations filter)" }
                return@forEach
            }
            val plugin = toolRegistryService.findPlugin(config.integrationType)
            if (plugin == null) {
                logger.warn {
                    "[ToolResolver] No plugin found for integrationType='${config.integrationType}' (config id=${config.metadata.id}) — skipping"
                }
                return@forEach
            }
            extractTools(agentIntegrations, config, plugin, namespaceId, null, agentName, resolved)
        }

        logger.info { "[ToolResolver] Total tools for namespace $namespaceId: ${resolved.size}" }
        return resolved.values
    }

    /**
     * Resolves the tool set for a user-scoped agent run, applying 3-tier overlay
     * reconciliation for each integration config.
     *
     * @param namespaceId The namespace to resolve tools for.
     * @param userId The authenticated user whose overlays should be applied.
     * @param agentIntegrations Optional integration filter from [AgentConfig.integrations].
     *   When null, all namespace integrations are included.
     * @param agentName The name of the agent being built, or null when resolving outside
     *   an agent context. Passed into [ToolContext] so plugins can exclude the calling
     *   agent from lists they build (e.g. the redirect tool excludes itself as a target).
     */
    fun resolveToolsForRun(
        namespaceId: UUID,
        userId: UUID,
        agentIntegrations: Map<String, List<String>?>? = null,
        agentName: String? = null,
    ): Collection<StandardTool<*>> {
        val resolved = mutableMapOf<String, StandardTool<*>>()

        val sharedConfigs = integrationConfigService.findByNamespaceShared(namespaceId)
        val userOverrides =
            integrationConfigService
                .findByUserId(userId)
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

            val config =
                try {
                    integrationConfigMergeService.resolve(namespaceId, userId, name)
                } catch (e: ConfigNotFoundException) {
                    if (name in sharedNames) {
                        logger.error { "[ToolResolver] Reconciliation failed for namespace-shared name='$name' — failing the run" }
                        throw e
                    }
                    logger.warn { "[ToolResolver] Dormant user override for name='$name' (no matching shared config): ${e.message}" }
                    return@forEach
                }

            val plugin =
                toolRegistryService.findPlugin(config.integrationType) ?: run {
                    logger.warn { "[ToolResolver] No plugin for integrationType='${config.integrationType}' (name='$name')" }
                    return@forEach
                }
            extractTools(agentIntegrations, config, plugin, namespaceId, userId, agentName, resolved)
        }

        logger.info { "[ToolResolver] Total tools for namespace $namespaceId (user $userId): ${resolved.size}" }
        return resolved.values
    }

    private fun extractTools(
        agentIntegrations: Map<String, List<String>?>?,
        config: IntegrationConfig,
        plugin: ToolPlugin,
        namespaceId: UUID,
        userId: UUID?,
        agentName: String?,
        resolved: MutableMap<String, StandardTool<*>>,
    ) {
        try {
            val allowedToolNames = agentIntegrations?.get(config.name)
            val tools =
                plugin
                    .provideTools(
                        config = config.parameters,
                        configName = config.name,
                        context = buildToolContext(namespaceId = namespaceId, userId = userId, agentName = agentName),
                    ).filter { tool -> isToolAllowed(tool.name, config.name, allowedToolNames) }
            tools.forEach { tool ->
                if (resolved.containsKey(tool.name)) {
                    logger.warn {
                        "[ToolResolver] Tool name conflict: '${tool.name}' from integrationType='${config.integrationType}' overrides an existing entry"
                    }
                }
                resolved[tool.name] = tool
            }
            logger.info {
                "[ToolResolver] Resolved ${tools.size} tool(s) from integrationType='${config.integrationType}' for namespace $namespaceId"
            }
        } catch (e: Exception) {
            logger.error(e) { "[ToolResolver] Error instantiating tools for integrationType='${config.integrationType}': ${e.message}" }
        }
    }

    private fun buildToolContext(
        namespaceId: UUID,
        userId: UUID?,
        agentName: String?,
    ) = ToolContext(
        namespaceId = namespaceId,
        userId = userId,
        userExternalId = null,
        caseEvents = emptyList(),
        agentName = agentName,
    )

    internal fun isToolAllowed(
        toolName: String,
        integrationKey: String,
        allowedNames: List<String>?,
    ): Boolean {
        if (allowedNames == null) return true
        return allowedNames.any { allowed ->
            toolName == allowed || toolName == "${integrationKey}__$allowed"
        }
    }

    companion object : KLogging()
}
