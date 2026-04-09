package io.whozoss.agentos.llmConfig

import io.whozoss.agentos.entity.EntityService
import java.util.UUID

/**
 * Service for managing [LlmConfig] entities.
 *
 * Extends [EntityService] with a point lookup by the natural key (namespaceId, name),
 * used internally to enforce the uniqueness constraint on [create].
 */
interface LlmConfigService : EntityService<LlmConfig, UUID> {
    /**
     * Find a single [LlmConfig] by its natural key within a namespace.
     *
     * @return The config if found and not removed, null otherwise.
     */
    fun findByNamespaceAndName(
        namespaceId: UUID,
        name: String,
    ): LlmConfig?
}
