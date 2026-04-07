package io.whozoss.agentos.integrationConfig

import io.whozoss.agentos.entity.EntityService
import java.util.UUID

/**
 * Service for managing [IntegrationConfig] entities.
 *
 * Extends [EntityService] with integration-specific operations:
 * - [upsert]: create-or-update by (namespaceId, name) — enforces the uniqueness constraint
 *   that only one config per integration name may exist within a namespace.
 * - [findByNamespaceAndName]: point lookup by the natural key.
 * - [findAll]: cross-namespace scan used by [io.whozoss.agentos.tool.ToolRegistryService]
 *   to resolve plugin configurations at startup.
 */
interface IntegrationConfigService : EntityService<IntegrationConfig, UUID> {
    /**
     * Return all [IntegrationConfig] entities across all namespaces.
     *
     * Intended for use by [io.whozoss.agentos.tool.ToolRegistryService] at startup to
     * resolve plugin configurations before the registry is namespace-aware.
     * Excludes removed entities.
     */
    fun findAll(): List<IntegrationConfig>
    /**
     * Create or update an [IntegrationConfig] identified by its natural key (namespaceId, name).
     *
     * If no config exists for that (namespaceId, name) pair, a new entity is created.
     * If one already exists, its [IntegrationConfig.integrationType] and
     * [IntegrationConfig.parameters] are replaced with the values from [config].
     *
     * @return The persisted (created or updated) entity.
     */
    fun upsert(config: IntegrationConfig): IntegrationConfig

    /**
     * Find a single [IntegrationConfig] by its natural key.
     *
     * @return The config if found and not removed, null otherwise.
     */
    fun findByNamespaceAndName(
        namespaceId: UUID,
        name: String,
    ): IntegrationConfig?
}
