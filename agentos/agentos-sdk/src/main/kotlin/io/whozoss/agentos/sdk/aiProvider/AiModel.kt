package io.whozoss.agentos.sdk.aiProvider

import com.fasterxml.jackson.annotation.JsonIgnoreProperties
import io.whozoss.agentos.sdk.entity.Entity
import io.whozoss.agentos.sdk.entity.EntityMetadata
import java.util.UUID

/**
 * Persistent configuration for accessing a specific AI model under a provider.
 *
 * Each [AiModel] belongs to one [io.whozoss.agentos.aiProvider.AiProviderConfig] (via [])
 * and describes how to invoke a particular model: its real API name, optional stable
 * alias, and inference parameters.
 *
 * [namespaceId] and [userId] are denormalised from the parent [AiProvider] at creation
 * time so that namespace-scoped or user-scoped queries can be served with a single
 * index lookup, without joining through [AiProvider]. This mirrors the pattern used
 * throughout the codebase (e.g. [io.whozoss.agentos.caseFlow.Case] carries namespaceId
 * directly rather than traversing a graph relationship).
 *
 * [apiModelName] is the real API model identifier sent to the provider (e.g. "claude-haiku-4-5").
 * [alias] is an optional stable contract name (e.g. "SMALL", "BIG") that agent definitions
 * can reference without knowing the underlying model.
 *
 * [priority] controls resolution order when multiple configs share the same alias or apiName
 * within a namespace. Higher value wins. Defaults to 0 — all configs are equal unless
 * explicitly prioritised. Ties are broken by insertion order (first created wins).
 *
 * [maxCompletionTokens] limits the number of tokens the model may generate in a single
 * response (i.e. the output / completion side). It does **not** control the total context
 * window — the provider enforces that separately. Null means no explicit limit is set and
 * the provider uses its own default.
 *
 * Uniqueness constraints enforced by [AiModelConfigServiceImpl]:
 * - (aiProviderId, apiName) must be unique
 * - (aiProviderId, alias) must be unique when alias is non-null
 *
 * Parent: AiProvider (via []).
 */
@JsonIgnoreProperties(ignoreUnknown = true)
data class AiModel(
    override val metadata: EntityMetadata = EntityMetadata(),
    val aiProviderId: UUID,
    val namespaceId: UUID? = null,
    val userId: UUID? = null,
    val apiModelName: String,
    val description: String? = null,
    val alias: String? = null,
    val priority: Int = 0,
    val temperature: Double? = null,
    /**
     * Maximum number of tokens the model may generate in a single response (completion
     * side only). Does not affect the input / context window. Null = provider default.
     */
    val maxCompletionTokens: Int? = null,
    /**
     * Total context window size in tokens (input + output combined), as advertised by
     * the provider. Used at runtime to guard against prompts that exceed the model's
     * capacity. Null means unknown / not configured. [Long] to accommodate models with
     * windows exceeding [Int.MAX_VALUE] (up to ~2 billion).
     */
    val contextWindow: Long? = null,
    /**
     * Optional per-million-token pricing configuration (USD). Null means no pricing
     * is configured and cost estimation is skipped. See [ModelPricing] for field semantics.
     */
    val pricing: ModelPricing? = null,
) : Entity
