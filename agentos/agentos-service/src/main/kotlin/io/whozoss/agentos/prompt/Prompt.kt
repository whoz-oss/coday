package io.whozoss.agentos.prompt

import io.whozoss.agentos.sdk.entity.Entity
import io.whozoss.agentos.sdk.entity.EntityMetadata
import java.util.UUID

/**
 * A named, parameterised prompt template.
 *
 * [content] is an ordered, non-empty list of strings forming the prompt body.
 * Each element may contain {{paramName}} placeholders resolved at render time
 * using entries from [parameters].
 *
 * Scope is determined by the `(namespaceId, userId)` pair:
 * - `(null, null)`   -> platform-level (visible to all, Super Admin write only)
 * - `(ns, null)`     -> namespace-scoped
 * - `(null, user)`   -> user-global overlay
 * - `(ns, user)`     -> user x namespace overlay
 *
 * [PromptParameter.defaultValue] is required (non-nullable) — every parameter
 * must declare a default so the prompt can be rendered without caller-supplied
 * values. An empty string is a valid default for optional free-form parameters.
 *
 * [sourceLanguage] is the BCP-47 code of the language [content] was authored in.
 * Defaults to `"en"`. The translation endpoint uses it to short-circuit when the
 * requested language matches the source (no LLM call needed).
 *
 * [translations] stores lazily-generated translations of [content] keyed by
 * BCP-47 language code. Each value is a `List<String>` mirroring [content] by
 * index. Populated by `PromptTranslationService` and cleared automatically by
 * `PromptServiceImpl.update` whenever [content] changes.
 */
data class PromptParameter(
    val name: String,
    val description: String? = null,
    val defaultValue: String,
)

data class Prompt(
    override val metadata: EntityMetadata = EntityMetadata(),
    val namespaceId: UUID? = null,
    val userId: UUID? = null,
    /**
     * Optional link to an [io.whozoss.agentos.agentConfig.AgentConfig].
     * When set, this prompt acts as a Starter for that agent.
     * Immutable post-creation — preserved on PUT by the controller.
     * Materialised as a `(:Prompt)-[:BELONGS_TO]->(:AgentConfig)` edge in Neo4j.
     */
    val agentConfigId: UUID? = null,
    val name: String,
    val description: String? = null,
    val content: List<String>,
    val parameters: List<PromptParameter> = emptyList(),
    /**
     * Opaque metadata map for external consumers (Copilot, Studio).
     * AgentOS persists this field as-is without interpreting its content.
     */
    val externalMetadata: Map<String, Any?>? = null,
    val sourceLanguage: String = "en",
    val translations: Map<String, List<String>>? = null,
) : Entity
