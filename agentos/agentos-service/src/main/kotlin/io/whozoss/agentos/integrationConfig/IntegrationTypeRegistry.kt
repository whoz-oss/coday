package io.whozoss.agentos.integrationConfig

import io.whozoss.agentos.sdk.tool.ToolPlugin

/**
 * Registry of available integration types.
 *
 * Returns the list of [IntegrationTypeDescriptor]s that the system knows how to handle.
 * Each descriptor carries a JSON Schema that describes the parameters an [IntegrationConfig]
 * of that type must supply.
 *
 * Descriptors come from two sources, merged at startup:
 * - Plugin-contributed: each loaded [ToolPlugin] that declares a non-null [ToolPlugin.configSchema]
 *   registers itself via [registerFromPlugin].
 * - Hardcoded fallback: static descriptors for integration types that have no plugin yet
 *   (JIRA, GITHUB, SLACK) remain in [HardcodedIntegrationTypeRegistry].
 *
 * The active implementation is [CompositeIntegrationTypeRegistry].
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
     * If a descriptor for [plugin.integrationType] already exists (e.g. from the hardcoded
     * fallback), the plugin-contributed one takes precedence.
     *
     * Plugins with a null [ToolPlugin.configSchema] are silently ignored — they need no
     * configuration and therefore have no descriptor to expose.
     */
    fun registerFromPlugin(plugin: ToolPlugin)
}
