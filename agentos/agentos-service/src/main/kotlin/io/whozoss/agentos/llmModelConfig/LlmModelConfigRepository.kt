package io.whozoss.agentos.llmModelConfig

import io.whozoss.agentos.entity.EntityRepository
import java.util.UUID

/**
 * Repository for [LlmModelConfig] persistence.
 *
 * [LlmModelConfig] entities are scoped to an [io.whozoss.agentos.llmConfig.LlmConfig]:
 * [findByParent] returns all non-removed model configs belonging to a given [llmConfigId].
 */
interface LlmModelConfigRepository : EntityRepository<LlmModelConfig, UUID>
