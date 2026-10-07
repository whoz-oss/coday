package io.whozoss.agentos.sdk.api.prompt

import io.swagger.v3.oas.annotations.media.Schema
import java.util.UUID

/**
 * Single entry in the response of `POST /api/prompts/translations/{languageCode}`.
 *
 * [id] identifies the prompt this translation belongs to.
 * [title] is null when the prompt has no [PromptDto.title].
 * [content] always has the same size as [PromptDto.content].
 */
@Schema(name = "PromptBatchTranslation")
data class PromptBatchTranslationDto(
    @field:Schema(description = "ID of the prompt.", format = "uuid")
    val id: UUID,

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
