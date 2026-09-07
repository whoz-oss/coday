package io.whozoss.agentos.sdk.api.prompt

import io.swagger.v3.oas.annotations.media.Schema

/**
 * Response body for `POST /api/prompts/{id}/translations/{languageCode}`.
 *
 * Carries the resolved translation for both [title] and [content] in the requested language.
 * Either field may originate from the source (when the language matches [PromptDto.sourceLanguage]),
 * a cache hit, or a fresh LLM call — the caller does not need to distinguish these cases.
 *
 * [title] is null when the prompt has no [PromptDto.title].
 * [content] always has the same size as [PromptDto.content].
 */
@Schema(name = "PromptTranslation")
data class PromptTranslationDto(
    @field:Schema(
        description = "Translated display label. Null when the prompt has no title.",
        nullable = true,
    )
    val title: String?,
    @field:Schema(
        description = "Translated content list. Same indices as the source content.",
    )
    val content: List<String>,
)
