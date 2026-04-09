package io.whozoss.agentos.llmConfig

import io.whozoss.agentos.entity.EntityRepository
import java.util.UUID

/**
 * Repository for [LlmConfig] persistence.
 *
 * [LlmConfig] entities are scoped to a namespace: [findByParent] returns all
 * non-removed configs belonging to a given [namespaceId].
 *
 * No custom query methods beyond the base interface are needed: the uniqueness
 * check on (namespaceId, name) is performed in [LlmConfigServiceImpl] using
 * [findByParent] + a linear scan, consistent with the [IntegrationConfigRepository]
 * pattern.
 */
interface LlmConfigRepository : EntityRepository<LlmConfig, UUID>
