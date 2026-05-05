package io.whozoss.agentos.aiModel

import io.whozoss.agentos.entity.EntityRepository
import io.whozoss.agentos.sdk.aiProvider.AiModel
import java.util.UUID

/**
 * Repository for [AiModel] persistence.
 *
 * Primary parent is [aiProviderId]: [findByParent] returns all non-removed model
 * configs belonging to a given provider config.
 *
 * [findByNamespaceId] enables namespace-scoped listing without joining through
 * [io.whozoss.agentos.aiProvider.AiProvider], using the denormalised [namespaceId]
 * property stored directly on each node.
 */
interface AiModelRepository : EntityRepository<AiModel, UUID> {
    /**
     * Find all non-removed model configs belonging to a namespace, across all
     * provider configs within that namespace.
     */
    fun findByNamespaceId(namespaceId: UUID): List<AiModel>

    /**
     * Find the first non-removed model config under [aiProviderId] whose [AiModel.apiModelName]
     * matches [apiName] (exact, case-sensitive). Returns null if none found.
     */
    fun findByAiProviderAndApiName(
        aiProviderId: UUID,
        apiName: String,
    ): AiModel?

    /**
     * Find the first non-removed model config under [aiProviderId] whose [AiModel.alias]
     * matches [alias] (exact, case-sensitive). Returns null if none found.
     */
    fun findByAiProviderAndAlias(
        aiProviderId: UUID,
        alias: String,
    ): AiModel?

    /**
     * Find all non-removed model configs scoped to the given user, regardless of [AiModel.namespaceId].
     */
    fun findByUserId(userId: UUID): List<AiModel>

    /**
     * Find a single non-removed model config matching the (namespaceId, userId, name) triple,
     * where `name` matches [AiModel.alias] first, falling back to [AiModel.apiModelName] when
     * alias is null. NULL id parameters match rows where the corresponding column is NULL.
     */
    fun findByTriple(
        namespaceId: UUID?,
        userId: UUID?,
        name: String,
    ): AiModel?
}
