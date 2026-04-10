package io.whozoss.agentos.integrationConfig

import io.whozoss.agentos.entity.EntityService
import java.util.UUID

/**
 * Service for managing [IntegrationConfig] entities.
 *
 * Extends [EntityService] with integration-specific operations:
 * - [create] and [update] are kept separate (inherited from [EntityService]) because
 *   each will carry distinct business logic as the feature matures (e.g. validation
 *   against the integration type schema, credential encryption, audit events).
 * - [findByNamespaceAndName]: point lookup by the natural key (namespaceId, name).
 */
interface IntegrationConfigService : EntityService<IntegrationConfig, UUID> {
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
