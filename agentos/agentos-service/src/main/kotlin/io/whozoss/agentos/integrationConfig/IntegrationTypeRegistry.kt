package io.whozoss.agentos.integrationConfig

/**
 * Registry of available integration types.
 *
 * Returns the list of [IntegrationTypeDescriptor]s that the system knows how to handle.
 * Each descriptor carries a JSON Schema that describes the parameters an [IntegrationConfig]
 * of that type must supply.
 *
 * The current implementation ([HardcodedIntegrationTypeRegistry]) returns a static list.
 * Future implementations will aggregate contributions from loaded [io.whozoss.agentos.sdk.tool.ToolPlugin]s,
 * allowing plugins to declare their own configuration schema alongside their tools.
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
}
