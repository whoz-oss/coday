package io.whozoss.agentos.aiModel

import io.whozoss.agentos.entity.EntityRepository
import io.whozoss.agentos.sdk.aiProvider.AiModel
import java.util.UUID

/**
 * Repository for [AiModel] persistence.
 *
 * Primary parent is [llmConfigId]: [findByParent] returns all non-removed model
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
     * Find the first non-removed model config under [llmConfigId] whose [AiModel.apiName]
     * matches [apiName] (exact, case-sensitive). Returns null if none found.
     */
    fun findByLlmConfigAndApiName(
        llmConfigId: UUID,
        apiName: String,
    ): AiModel?

    /**
     * Find the first non-removed model config under [llmConfigId] whose [AiModel.alias]
     * matches [alias] (exact, case-sensitive). Returns null if none found.
     */
    fun findByLlmConfigAndAlias(
        llmConfigId: UUID,
        alias: String,
    ): AiModel?
}
