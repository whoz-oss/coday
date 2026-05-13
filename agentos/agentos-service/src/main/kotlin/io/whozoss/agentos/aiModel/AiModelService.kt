package io.whozoss.agentos.aiModel

import io.whozoss.agentos.entity.EntityService
import io.whozoss.agentos.sdk.aiProvider.AiModel
import java.util.UUID

/**
 * Service for managing [AiModel] entities.
 *
 * Extends [EntityService] with point lookups by natural keys and namespace-scoped listing.
 */
interface AiModelService : EntityService<AiModel, UUID> {
    /**
     * Find a single [AiModel] by provider config and real API model name.
     */
    fun findByAiProviderAndApiName(
        aiProviderId: UUID,
        apiName: String,
    ): AiModel?

    /**
     * Find a single [AiModel] by provider config and alias.
     *
     * Used for alias-based resolution: an agent definition references "SMALL",
     * the service resolves which model (and therefore which provider) to use.
     */
    fun findByAiProviderAndAlias(
        aiProviderId: UUID,
        alias: String,
    ): AiModel?

    /**
     * Find all [AiModel] belonging to a namespace, across all provider configs.
     *
     * Uses the denormalised [AiModel.namespaceId] property so no join through
     * [io.whozoss.agentos.aiProvider.AiProvider] is needed.
     */
    fun findByNamespaceId(namespaceId: UUID): List<AiModel>

    /**
     * Find the [AiModel] for [name] within the given namespace.
     *
     * Resolution order:
     * 1. Match [AiModel.alias] (case-insensitive). If one or more configs match,
     *    return the one with the highest [AiModel.priority].
     * 2. If no alias matches, fall back to [AiModel.apiModelName] (case-insensitive)
     *    and again return the highest-priority match.
     *
     * The default value of [name] is `"default"`, which is the conventional alias
     * for the primary model in a namespace.
     *
     * Returns `null` when neither alias nor apiName matches anything in the namespace.
     */
    fun findAiModel(
        namespaceId: UUID,
        name: String = "default",
    ): AiModel?

    /**
     * Find all non-removed [AiModel] scoped to the given user, regardless of [AiModel.namespaceId].
     * Used by [AiModelController.list] user-scope branches.
     */
    fun findByUserId(userId: UUID): List<AiModel>

    /**
     * Scope-aware filtered listing used by [AiModelController.list].
     *
     * Dispatches the query based on the resolved namespace/user/provider filter combination:
     * - When [aiProviderId] is set, results are scoped to that provider (guarded by [canSeeProvider])
     * - When a specific namespace is given with no user request -> namespace-shared rows
     * - When user is requested -> user-scoped rows, optionally filtered by namespace
     * - No filters -> caller's own overlays
     *
     * @param namespaceId resolved namespace UUID (null when absent or `none` sentinel)
     * @param namespaceIsNone true when the raw query parameter was the `none` sentinel
     * @param callerId the authenticated user's id (always provided)
     * @param userRequested true when the caller explicitly passed `userId=me`
     * @param aiProviderId optional provider filter
     * @param canReadNamespace callback to check caller READ permission on a namespace
     * @param canSeeProvider callback to check caller visibility on a provider
     */
    fun findFiltered(
        namespaceId: UUID?,
        namespaceIsNone: Boolean,
        callerId: UUID,
        userRequested: Boolean,
        aiProviderId: UUID?,
        canReadNamespace: (UUID) -> Boolean,
        canSeeProvider: (UUID) -> Boolean,
    ): List<AiModel>
}
