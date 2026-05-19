package io.whozoss.agentos.aiProvider

import io.whozoss.agentos.entity.EntityService
import io.whozoss.agentos.permissions.EntityType
import io.whozoss.agentos.reconciliation.ConfigLookup
import io.whozoss.agentos.sdk.aiProvider.AiProvider
import io.whozoss.agentos.security.declarative.OwnershipAware
import java.util.UUID

/**
 * Service for managing [AiProvider] entities.
 *
 * Extends [EntityService] with scope-aware listing, and implements [ConfigLookup] so the
 * generic [io.whozoss.agentos.reconciliation.ConfigMergeService] can resolve overlays
 * without an intermediate wrapper bean.
 */
interface AiProviderService : EntityService<AiProvider, UUID>, ConfigLookup<AiProvider>, OwnershipAware {
    override val ownershipEntityType: EntityType get() = EntityType.AI_PROVIDER
    override fun resolveOwner(targetId: UUID): UUID? = findById(targetId)?.userId
    /**
     * Find all [AiProvider] scoped to the given namespace.
     */
    fun findByNamespaceId(namespaceId: UUID): List<AiProvider>

    /**
     * Find all [AiProvider] scoped to the given user.
     */
    fun findByUserId(userId: UUID): List<AiProvider>

    /**
     * Scope-aware filtered listing used by [io.whozoss.agentos.aiProvider.AiProviderController.list].
     *
     * Dispatches the query based on the resolved namespace/user filter combination:
     * - Specific namespace + no user request -> namespace-shared (guarded by [canReadNamespace])
     * - User requested -> user-scoped rows, optionally filtered by namespace
     * - No filters -> caller's own overlays
     *
     * @param namespaceId resolved namespace UUID (null when absent or `none` sentinel)
     * @param namespaceIsNone true when the raw query parameter was the `none` sentinel
     * @param callerId the authenticated user's id (always provided)
     * @param userRequested true when the caller explicitly passed `userId=me`
     * @param canReadNamespace callback to check caller READ permission on the namespace
     */
    fun findFiltered(
        namespaceId: UUID?,
        namespaceIsNone: Boolean,
        callerId: UUID,
        userRequested: Boolean,
        canReadNamespace: (UUID) -> Boolean,
    ): List<AiProvider>
}
