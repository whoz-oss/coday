package io.whozoss.agentos.llmModelConfig

import io.whozoss.agentos.entity.EntityRepository
import java.util.UUID

/**
 * Repository for [LlmModelConfig] persistence.
 *
 * Primary parent is [llmConfigId]: [findByParent] returns all non-removed model
 * configs belonging to a given provider config.
 *
 * [findByNamespaceId] enables namespace-scoped listing without joining through
 * [io.whozoss.agentos.llmConfig.LlmConfig], using the denormalised [namespaceId]
 * property stored directly on each node.
 */
interface LlmModelConfigRepository : EntityRepository<LlmModelConfig, UUID> {
    /**
     * Find all non-removed model configs belonging to a namespace, across all
     * provider configs within that namespace.
     */
    fun findByNamespaceId(namespaceId: UUID): List<LlmModelConfig>
}
