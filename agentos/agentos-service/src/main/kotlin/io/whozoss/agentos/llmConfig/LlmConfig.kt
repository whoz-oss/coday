package io.whozoss.agentos.llmConfig

import com.fasterxml.jackson.annotation.JsonIgnoreProperties
import io.whozoss.agentos.sdk.aiProvider.AiApiType
import io.whozoss.agentos.sdk.entity.Entity
import io.whozoss.agentos.sdk.entity.EntityMetadata
import java.util.UUID

/**
 * Persistent LLM provider configuration scoped to a namespace.
 *
 * A single [LlmConfig] represents one provider connection (e.g. "anthropic", "internal-llm")
 * with its credentials and a list of model entries available under that provider.
 *
 * Uniqueness constraint: (namespaceId, name) must be unique — enforced by [LlmConfigServiceImpl].
 *
 * [apiKey] is stored in clear text internally but is always masked in API responses
 * via [LlmConfigController.toResource]. On update, a masked value sent by the client
 * is detected and replaced with the persisted original (see [LlmConfigController.update]).
 *
 * Parent: Namespace (via [namespaceId]).
 */
@JsonIgnoreProperties(ignoreUnknown = true)
data class LlmConfig(
    override val metadata: EntityMetadata = EntityMetadata(),
    val namespaceId: UUID,
    val name: String,
    val apiType: AiApiType,
    val baseUrl: String? = null,
    val apiKey: String? = null,
    val models: List<LlmModelEntry> = emptyList(),
) : Entity

/**
 * Value object describing a single model available under an [LlmConfig] provider.
 *
 * [apiName] is the real API model identifier (e.g. "claude-haiku-4-5", "gpt-4o").
 * [alias] is an optional stable contract name (e.g. "SMALL", "BIG") that agent
 * definitions can reference without knowing the underlying model — swapping a model
 * only requires updating the alias mapping here, not every agent definition.
 *
 * Not an [Entity]: no independent lifecycle, no UUID. Lives entirely within its
 * parent [LlmConfig]'s list.
 */
@JsonIgnoreProperties(ignoreUnknown = true)
data class LlmModelEntry(
    val apiName: String,
    val alias: String? = null,
    val displayName: String? = null,
    val temperature: Double? = null,
    val maxTokens: Int? = null,
)
