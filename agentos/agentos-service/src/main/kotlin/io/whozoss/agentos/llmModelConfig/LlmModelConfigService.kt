package io.whozoss.agentos.llmModelConfig

import io.whozoss.agentos.entity.EntityService
import java.util.UUID

/**
 * Service for managing [LlmModelConfig] entities.
 *
 * Extends [EntityService] with point lookups by natural keys and namespace-scoped listing.
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

    /**
     * Find all [LlmModelConfig] belonging to a namespace, across all provider configs.
     *
     * Uses the denormalised [LlmModelConfig.namespaceId] property so no join through
     * [io.whozoss.agentos.llmConfig.LlmConfig] is needed.
     */
    fun findByNamespaceId(namespaceId: UUID): List<LlmModelConfig>

    /**
     * Find the [LlmModelConfig] whose [LlmModelConfig.alias] matches [name]
     * (case-insensitive) within the given namespace, preferring the highest
     * [LlmModelConfig.priority] when multiple entries share the same alias.
     *
     * The default value of [name] is `"default"`, which is the conventional alias
     * for the primary model in a namespace.
     *
     * Returns `null` when no config in the namespace carries a matching alias.
     */
    fun findModelConfig(
        namespaceId: UUID,
        name: String = "default",
    ): LlmModelConfig?
}
