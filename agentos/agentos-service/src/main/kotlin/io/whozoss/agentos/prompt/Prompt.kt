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
 * [namespaceId] is null for platform-level prompts (visible to all) and
 * non-null for namespace-scoped prompts.
 */
data class PromptParameter(
    val name: String,
    val description: String? = null,
    val defaultValue: String? = null,
)

data class Prompt(
    override val metadata: EntityMetadata = EntityMetadata(),
    val namespaceId: UUID? = null,
    val name: String,
    val description: String? = null,
    val content: List<String>,
    val parameters: List<PromptParameter> = emptyList(),
) : Entity
