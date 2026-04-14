package io.whozoss.agentos.aiProvider

import io.whozoss.agentos.entity.EntityService
import io.whozoss.agentos.sdk.aiProvider.AiProvider
import java.util.UUID

/**
 * Service for managing [AiProvider] entities.
 *
 * Extends [EntityService] with scope-aware listing and a point lookup by natural key.
 */
interface AiProviderService : EntityService<AiProvider, UUID> {
    /**
     * Find all [AiProvider] scoped to the given namespace.
     */
    fun findByNamespaceId(namespaceId: UUID): List<AiProvider>

    /**
     * Find all [AiProvider] scoped to the given user.
     */
    fun findByUserId(userId: UUID): List<AiProvider>

    /**
     * Find a single [AiProvider] by its natural key.
     *
     * [namespaceId] and [userId] may each be null; the lookup matches only configs
     * where both fields equal the provided values (including null equality).
     */
    fun findByNamespaceAndUserAndName(
        namespaceId: UUID?,
        userId: UUID?,
        name: String,
    ): AiProvider?
}
