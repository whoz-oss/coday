package io.whozoss.agentos.llmModelConfig

import com.fasterxml.jackson.annotation.JsonIgnoreProperties
import io.whozoss.agentos.sdk.entity.Entity
import io.whozoss.agentos.sdk.entity.EntityMetadata
import java.util.UUID

/**
 * Persistent configuration for accessing a specific LLM model under a provider.
 *
 * Each [LlmModelConfig] belongs to one [io.whozoss.agentos.llmConfig.LlmConfig] (via [llmConfigId])
 * and describes how to invoke a particular model: its real API name, optional stable
 * alias, and inference parameters.
 *
 * [namespaceId] and [userId] are denormalised from the parent [LlmConfig] at creation
 * time so that namespace-scoped or user-scoped queries can be served with a single
 * index lookup, without joining through [LlmConfig]. This mirrors the pattern used
 * throughout the codebase (e.g. [io.whozoss.agentos.caseFlow.Case] carries namespaceId
 * directly rather than traversing a graph relationship).
 *
 * [apiName] is the real API model identifier sent to the provider (e.g. "claude-haiku-4-5").
 * [alias] is an optional stable contract name (e.g. "SMALL", "BIG") that agent definitions
 * can reference without knowing the underlying model.
 *
 * Uniqueness constraints enforced by [LlmModelConfigServiceImpl]:
 * - (llmConfigId, apiName) must be unique
 * - (llmConfigId, alias) must be unique when alias is non-null
 *
 * Parent: LlmConfig (via [llmConfigId]).
 */
@JsonIgnoreProperties(ignoreUnknown = true)
data class LlmModelConfig(
    override val metadata: EntityMetadata = EntityMetadata(),
    val llmConfigId: UUID,
    val namespaceId: UUID,
    val userId: UUID? = null,
    val apiName: String,
    val alias: String? = null,
    val displayName: String? = null,
    val temperature: Double? = null,
    val maxTokens: Int? = null,
) : Entity
