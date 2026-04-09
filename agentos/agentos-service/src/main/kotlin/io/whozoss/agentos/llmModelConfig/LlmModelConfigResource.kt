package io.whozoss.agentos.llmModelConfig

import io.swagger.v3.oas.annotations.media.Schema
import jakarta.validation.constraints.NotBlank
import jakarta.validation.constraints.NotNull
import java.util.UUID

/**
 * HTTP resource (DTO) for [LlmModelConfig] entities.
 *
 * [namespaceId] and [userId] are read-only from the client perspective: they are
 * resolved server-side from the parent [io.whozoss.agentos.llmConfig.LlmConfig]
 * at creation time and must not be overridden by the caller.
 *
 * Annotated with @Schema(name = "LlmModelConfig") so the generated OpenAPI spec uses
 * the clean name instead of "LlmModelConfigResource".
 */
@Schema(name = "LlmModelConfig")
data class LlmModelConfigResource(
    val id: UUID? = null,
    @field:NotNull
    val llmConfigId: UUID?,
    val namespaceId: UUID? = null,
    val userId: UUID? = null,
    @field:NotBlank
    val apiName: String,
    val alias: String? = null,
    val displayName: String? = null,
    val temperature: Double? = null,
    val maxTokens: Int? = null,
)
