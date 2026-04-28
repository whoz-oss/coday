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
 * ## Classloader safety
 *
 * Jackson types ([JsonNode]) cross the plugin/service boundary safely because
 * AgentOS configures PF4J with [org.pf4j.ClassLoadingStrategy.APD]: the
 * [org.pf4j.PluginClassLoader] delegates to the service classloader first, so
 * both sides always share the same [JsonNode] class instance. Plugins must
 * therefore declare Jackson as [compileOnly] and must NOT bundle it in their
 * fat JAR.
 *
 * Example usage:
 * ```kotlin
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
 *         return listOf(MyCustomTool(apiKey = apiKey))
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
     * @param config Parsed JSON parameters from the persisted IntegrationConfig,
     *               or null if no configuration is available.
     * @param configName The name of the IntegrationConfig being instantiated,
     *                   or null for config-less plugins.
     * @return List of tool implementations to register.
     */
    fun provideTools(config: JsonNode?, configName: String? = null): List<StandardTool<*>>
}
