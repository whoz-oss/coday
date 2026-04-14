package io.whozoss.agentos.aiModel

import io.swagger.v3.oas.annotations.media.Schema
import jakarta.validation.constraints.NotBlank
import jakarta.validation.constraints.NotNull
import java.util.UUID

/**
 * HTTP resource (DTO) for [AiModel] entities.
 *
 * [namespaceId] and [userId] are read-only from the client perspective: they are
 * resolved server-side from the parent [io.whozoss.agentos.aiProvider.AiProvider]
 * at creation time and must not be overridden by the caller.
 *
 * Annotated with @Schema(name = "AiModel") so the generated OpenAPI spec uses
 * the clean name instead of "AiModelResource".
 */
@Schema(name = "AiModel")
data class AiModelResource(
    val id: UUID? = null,
    @field:NotNull
    val aiProviderId: UUID?,
    val namespaceId: UUID? = null,
    val userId: UUID? = null,
    @field:NotBlank
    val apiName: String,
    val alias: String? = null,
    val priority: Int = 0,
    val temperature: Double? = null,
    val maxTokens: Int? = null,
)
