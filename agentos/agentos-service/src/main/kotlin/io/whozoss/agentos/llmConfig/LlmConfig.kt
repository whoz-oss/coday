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
 * with its credentials. The models available under this provider are managed as separate
 * [LlmModelConfig] entities (parent: this config's id).
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
) : Entity
