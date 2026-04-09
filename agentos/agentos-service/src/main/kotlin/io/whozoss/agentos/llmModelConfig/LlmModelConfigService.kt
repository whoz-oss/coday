package io.whozoss.agentos.llmModelConfig

import io.whozoss.agentos.entity.EntityService
import java.util.UUID

/**
 * Service for managing [LlmModelConfig] entities.
 *
 * Extends [EntityService] with point lookups by the two natural keys:
 * - (llmConfigId, apiName) — uniqueness constraint on create
 * - (llmConfigId, alias)   — future resolution path: agent asks for "SMALL",
 *   we find the matching [LlmModelConfig] and retrieve its parent [LlmConfig]
 *   to build the [io.whozoss.agentos.chat.ChatClientProvider] call
 */
interface LlmModelConfigService : EntityService<LlmModelConfig, UUID> {
    /**
     * Find a single [LlmModelConfig] by provider config and real API model name.
     */
    fun findByLlmConfigAndApiName(
        llmConfigId: UUID,
        apiName: String,
    ): LlmModelConfig?

    /**
     * Find a single [LlmModelConfig] by provider config and alias.
     *
     * Used for alias-based resolution: an agent definition references "SMALL",
     * the service resolves which model (and therefore which provider) to use.
     */
    fun findByLlmConfigAndAlias(
        llmConfigId: UUID,
        alias: String,
    ): LlmModelConfig?
}
