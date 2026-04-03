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
 *     override fun provideTools(config: JsonNode?): List<StandardTool<*>> {
 *         val apiKey = config?.get("apiKey")?.asText() ?: ""
 *         return listOf(MyCustomTool(apiKey))
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
     * Called once per plugin load. The returned tools are registered in the
     * [ToolRegistry] and remain active until the plugin is unloaded.
     *
     * @param config Parsed JSON parameters from the persisted IntegrationConfig,
     *               or null if no configuration is available.
     * @return List of tool implementations to register.
     */
    fun provideTools(config: JsonNode?): List<StandardTool<*>>
}
