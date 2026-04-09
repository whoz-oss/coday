package io.whozoss.agentos.llmConfig

import io.whozoss.agentos.entity.EntityService
import java.util.UUID

/**
 * Service for managing [LlmConfig] entities.
 *
 * Extends [EntityService] with scope-aware listing and a point lookup by natural key.
 */
interface LlmConfigService : EntityService<LlmConfig, UUID> {
    /**
     * Find all [LlmConfig] scoped to the given namespace.
     */
    fun findByNamespaceId(namespaceId: UUID): List<LlmConfig>

    /**
     * Find all [LlmConfig] scoped to the given user.
     */
    fun findByUserId(userId: UUID): List<LlmConfig>

    /**
     * Find a single [LlmConfig] by its natural key.
     *
     * [namespaceId] and [userId] may each be null; the lookup matches only configs
     * where both fields equal the provided values (including null equality).
     */
    fun findByNamespaceAndUserAndName(
        namespaceId: UUID?,
        userId: UUID?,
        name: String,
    ): LlmConfig?
}
