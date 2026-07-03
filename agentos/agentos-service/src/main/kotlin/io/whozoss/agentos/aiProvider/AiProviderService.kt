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
     * Find all non-removed platform-level [AiProvider] (namespaceId IS NULL AND userId IS NULL).
     * Readable by any authenticated user; writable only by super-admins.
     */
    fun findPlatformLevel(): List<AiProvider>

    /**
     * Resolve the effective [AiProvider] for a given (namespaceId, userId, name) triple by
     * fetching all applicable layers in a single query and folding them from lowest to highest
     * precedence via [AiProviderMergeStrategy].
     *
     * Precedence (lowest → highest): platform → namespace-shared → user-global → user×namespace.
     *
     * Throws [io.whozoss.agentos.exception.ConfigNotFoundException] when no layer contains
     * a provider with the given name.
     */
    fun resolveProvider(
        namespaceId: UUID,
        userId: UUID,
        name: String,
    ): AiProvider

    /**
     * Scope-aware filtered listing used by [io.whozoss.agentos.aiProvider.AiProviderController.list].
     *
     * Dispatches the query based on the resolved namespace/user filter combination:
     * - Specific namespace + no user request -> namespace-shared (READ permission checked internally)
     * - User requested -> user-scoped rows, optionally filtered by namespace
     * - No filters -> caller's own overlays
     * - Platform level (namespaceId=none, no userId) -> platform-level rows (open to all authenticated)
     *
     * @param namespaceId resolved namespace UUID (null when absent or `none` sentinel)
     * @param namespaceIsNone true when the raw query parameter was the `none` sentinel
     * @param callerId the authenticated user's id (always provided)
     * @param userRequested true when the caller explicitly passed `userId=me`
     */
    fun findFiltered(
        namespaceId: UUID?,
        namespaceIsNone: Boolean,
        callerId: UUID,
        userRequested: Boolean,
    ): List<AiProvider>
}
