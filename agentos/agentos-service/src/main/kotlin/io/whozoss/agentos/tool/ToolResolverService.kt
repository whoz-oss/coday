package io.whozoss.agentos.tool

import io.whozoss.agentos.integrationConfig.IntegrationConfig
import io.whozoss.agentos.integrationConfig.IntegrationConfigMergeStrategy
import io.whozoss.agentos.integrationConfig.IntegrationConfigService
import io.whozoss.agentos.sdk.tool.StandardTool
import io.whozoss.agentos.sdk.tool.ToolContext
import io.whozoss.agentos.sdk.tool.ToolPlugin
import mu.KLogging
import org.springframework.stereotype.Service
import kotlin.collections.flatten

@Service
class ToolResolverService(
    private val toolRegistryService: ToolRegistryService,
    private val integrationConfigService: IntegrationConfigService,
    private val mergeStrategy: IntegrationConfigMergeStrategy,
) {
    /**
     * Resolves the tool set for a user-scoped agent run, applying 3-tier overlay
     * reconciliation for each integration config.
     *
     * The [context] must carry both a valid [ToolContext.namespaceId] and a non-null
     * [ToolContext.userId] (the method throws [IllegalArgumentException] otherwise). The
     * context is passed verbatim to each plugin, giving them access to the full runtime
     * identity (namespace, user, external id, agent name, case events).
     *
     * @param agentIntegrations Optional integration filter from [AgentConfig.integrations].
     *   When null, all namespace integrations are included.
     * @param context Runtime context forwarded to each [ToolPlugin.provideTools] call.
     *   [ToolContext.userId] must be non-null.
     */
    fun resolveToolsForRun(
        agentIntegrations: Map<String, List<String>?>? = null,
        context: ToolContext,
    ): Collection<StandardTool<*>> {
        val allTools =
            resolveConfigs(
                integrationConfigNames = agentIntegrations?.keys?.toList() ?: emptyList(),
                context = context,
            ).mapNotNull { config ->
                toolRegistryService
                    .findPlugin(config.integrationType)
                    .also { if (it == null) logger.warn { "[ToolResolver] No plugin found for type ${config.integrationType}" } }
                    ?.let { plugin ->
                        extractTools(
                            allowedNames = agentIntegrations?.get(config.name),
                            config = config,
                            plugin = plugin,
                            context = context,
                        )
                    }
            }.flatten()

        // De-duplicate tools by name, dropping second and more tools with same name
        return allTools
            .groupBy { it.name }
            .map { (name, tools) ->
                if (tools.size > 1) {
                    logger.warn { "[ToolResolver] Tool name conflict: '$name' present ${tools.size} times, keeping the first one." }
                }
                tools.first()
            }
    }

    private fun resolveConfigs(
        integrationConfigNames: List<String>,
        context: ToolContext,
    ): List<IntegrationConfig> =
        integrationConfigService
            .findAllByNamesForNamespaceIdAndUserId(
                names = integrationConfigNames,
                namespaceId = context.namespaceId,
                userId = context.userId,
            ).groupBy { it.name }
            .mapNotNull { (_, configs) ->
                configs.sortedBy { it.getPriority() }.fold(
                    initial = configs.first(),
                ) { base, override -> mergeStrategy.merge(base, override) }
            }

    private fun extractTools(
        allowedNames: List<String>?,
        config: IntegrationConfig,
        plugin: ToolPlugin,
        context: ToolContext,
    ): List<StandardTool<*>>? {
        val tools =
            try {
                plugin.provideTools(
                    config = config.parameters,
                    configName = config.name,
                    context = context,
                )
            } catch (e: Exception) {
                logger.error(e) {
                    "[ToolResolver] Error instantiating tools for integration '${config.name}' " +
                        "(type '${config.integrationType}'): ${e.message}"
                }
                emptyList()
            }
        logger.trace {
            "[ToolResolver] Plugin ${config.integrationType} provided ${tools.size} tools " +
                "for integration '${config.name}': ${
                    tools.joinToString(
                        ", ",
                    ) { it.name }
                }"
        }
        return tools
            .filter { tool ->
                isToolAllowed(
                    toolName = tool.name,
                    integrationKey = config.name,
                    allowedNames = allowedNames,
                )
            }.also { tools ->
                logger.debug {
                    "[ToolResolver] Resolved ${tools.size} tools for integration ${config.name} on agent ${context.agentName}"
                }
            }
    }

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
