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
     * Used by [UserAiModelController.list] (story 6.3).
     */
    fun findByUserId(userId: UUID): List<AiModel>
}
