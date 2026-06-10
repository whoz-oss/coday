package io.whozoss.agentos.sdk.tool

import com.fasterxml.jackson.databind.JsonNode
import org.pf4j.ExtensionPoint

/**
 * Extension point for plugins that provide tools.
 *
 * A ToolPlugin is a typed factory: it declares what configuration it needs
 * ([integrationType], [configSchema]) and produces [StandardTool] instances
 * from a supplied configuration ([provideTools]).
 *
 * The service layer is responsible for:
 * 1. Collecting [configSchema] from each loaded plugin to populate the
 *    [IntegrationTypeRegistry] catalogue exposed to clients.
 * 2. Looking up the persisted [IntegrationConfig] for [integrationType] and
 *    passing its [parameters][IntegrationConfig.parameters] to [provideTools].
 *
 * Plugins that require no configuration should declare [configSchema] as null
 * and handle a null [config] in [provideTools] by using built-in defaults.
 *
 * Example usage:
 * ```kotlin
 * // 1. Plugin lifecycle class
 * class MyPlugin(wrapper: PluginWrapper) : Plugin(wrapper) {
 *     override fun start() { logger.info("Plugin started") }
 *     override fun stop() { logger.info("Plugin stopped") }
 * }
 *
 * // 2. Tool provider with @Extension
 * @Extension
 * class MyToolProvider : ToolPlugin {
 *     override val integrationType = "MY_INTEGRATION"
 *
 *     override val configSchema: JsonNode? = jacksonObjectMapper().readTree("""
 *         {
 *             "type": "object",
 *             "properties": {
 *                 "apiKey": { "type": "string", "title": "API Key" }
 *             },
 *             "required": ["apiKey"]
 *         }
 *     """)
 *
 *     override fun provideTools(config: JsonNode?, configName: String?): List<StandardTool<*>> {
 *         val apiKey = config?.get("apiKey")?.asText() ?: ""
 *         val prefix = configName?.let { "${it}__" } ?: ""
 *         return listOf(MyCustomTool(name = "${prefix}MyTool", apiKey = apiKey))
 *     }
 * }
 * ```
 */
interface ToolPlugin : ExtensionPoint {
    /**
     * Machine-readable identifier for this integration type.
     * Must be unique across all loaded plugins.
     * Matches [IntegrationConfig.integrationType] for config lookup.
     */
    val integrationType: String

    /**
     * JSON Schema (as a [JsonNode]) describing the configuration this plugin
     * expects to receive in [provideTools].
     *
     * The service exposes this schema via [IntegrationTypeRegistry] so clients
     * can render a configuration form dynamically.
     *
     * Return null if this plugin needs no configuration.
     */
    val configSchema: JsonNode?

    /**
     * Produce the tools this plugin contributes, instantiated from [config].
     *
     * [config] is the [IntegrationConfig.parameters] node stored for this
     * [integrationType] in the current context, or null if no config has been
     * persisted yet. Implementations must handle null gracefully by falling
     * back to sensible defaults.
     *
     * [configName] is the [IntegrationConfig.name] of the config being instantiated,
     * or null for config-less plugins. When a namespace has multiple configs of the
     * same [integrationType] (e.g. two JIRA instances), plugins should incorporate
     * [configName] into their tool names to avoid collisions in the registry
     * using the convention `configName__ToolName`
     * (e.g. "JIRA_PROD__GetIssue" and "JIRA_STAGING__GetIssue").
     *
     * [context] carries the resolution context: [ToolContext.namespaceId] scopes the
     * tool set to the correct namespace, and other fields (userId, caseEvents) are
     * available for plugins that need per-request identity or history at instantiation
     * time. Most plugins ignore [context] entirely — it is provided as a default
     * parameter to preserve binary compatibility with existing plugin JARs.
     *
     * @param config Parsed JSON parameters from the persisted IntegrationConfig,
     *               or null if no configuration is available.
     * @param configName The name of the IntegrationConfig being instantiated,
     *                   or null for config-less plugins.
     * @param context Resolution context for namespace-scoped or user-scoped tool factories.
     * @return List of tool implementations to register.
     */
    fun provideTools(
        config: JsonNode?,
        configName: String? = null,
        context: ToolContext? = null,
    ): List<StandardTool<*>>

    /**
     * Optionally contribute a dynamic, runtime description of this integration instance
     * to the namespace-level system prompt.
     *
     * Called once per [IntegrationConfig] when building the namespace system prompt so
     * the agent receives an up-to-date description of what this integration provides
     * within the current namespace. Because some integrations are remote (e.g. fetching
     * workspace info from an external API), this method is `suspend` and may perform
     * async I/O.
     *
     * When this method returns `null`, nothing is appended for this integration config.
     *
     * Implementations should be resilient: catch exceptions internally and return `null`
     * rather than letting errors propagate — a missing dynamic description is non-fatal.
     *
     * The default implementation returns `null` (no contribution), preserving binary
     * compatibility with existing plugin JARs.
     *
     * @param config Parsed JSON parameters from the persisted IntegrationConfig,
     *               or null if no configuration is available.
     * @param configName The name of the IntegrationConfig being described.
     * @param context Resolution context (namespaceId, userId, caseEvents).
     * @return A description string to append to the namespace system prompt, or null.
     */
    suspend fun describeNamespace(
        config: JsonNode?,
        configName: String?,
        context: ToolContext?,
    ): String? = null
}
