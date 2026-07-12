package io.whozoss.agentos.integrationConfig

import io.whozoss.agentos.sdk.tool.ToolPlugin

/**
 * Registry of available integration types.
 *
 * Returns the list of [IntegrationTypeDescriptor]s that the system knows how to handle.
 * Each descriptor carries a JSON Schema that describes the parameters an [IntegrationConfig]
 * of that type must supply.
 *
 * Descriptors are contributed at runtime by loaded [ToolPlugin]s that declare a non-null
 * [ToolPlugin.configSchema]. The active implementation is [CompositeIntegrationTypeRegistry].
 */
interface IntegrationTypeRegistry {
    /**
     * Return all known integration type descriptors, sorted by [IntegrationTypeDescriptor.type].
     */
    fun listTypes(): List<IntegrationTypeDescriptor>

    /**
     * Find a single descriptor by its [type] identifier, or null if unknown.
     */
    fun findByType(type: String): IntegrationTypeDescriptor?

    /**
     * Register a descriptor contributed by a loaded [ToolPlugin].
     *
     * Called by [io.whozoss.agentos.tool.ToolRegistryService] after each plugin is loaded.
     * Plugins with a null [ToolPlugin.configSchema] are silently ignored — they need no
     * configuration and therefore have no descriptor to expose.
     */
    fun registerFromPlugin(plugin: ToolPlugin)

    /**
     * Register a built-in integration descriptor not backed by a [ToolPlugin] config schema
     * (e.g. the file-exchange integrations). Such integrations are enabled by adding their
     * [IntegrationTypeDescriptor.type] to an agent's `integrations` map and are resolved
     * imperatively rather than through the plugin path.
     */
    fun registerBuiltIn(descriptor: IntegrationTypeDescriptor)
}
