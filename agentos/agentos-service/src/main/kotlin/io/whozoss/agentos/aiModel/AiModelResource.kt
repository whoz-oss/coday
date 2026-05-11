package io.whozoss.agentos.aiModel

import io.swagger.v3.oas.annotations.media.Schema
import jakarta.validation.constraints.NotBlank
import jakarta.validation.constraints.NotNull
import java.util.UUID

/**
 * HTTP resource (DTO) for [AiModel] entities.
 *
 * Request body : [namespaceId] and [userId] are silently ignored on POST/PUT — server-side
 * denormalized from the parent [io.whozoss.agentos.aiProvider.AiProvider] via
 * [AiModelServiceImpl.create]. [aiProviderId] is required and immutable after creation.
 *
 * Response body : [namespaceId] and [userId] carry the persisted denormalized values.
 *
 * Annotated with @Schema(name = "AiModel") so the generated OpenAPI spec uses
 * the clean name instead of "AiModelResource".
 */
@Schema(name = "AiModel")
data class AiModelResource(
    val id: UUID? = null,
    @field:NotNull
    val aiProviderId: UUID?,
    @field:Schema(types = ["string", "null"], format = "uuid")
    val namespaceId: UUID? = null,
    @field:Schema(types = ["string", "null"], format = "uuid")
    val userId: UUID? = null,
    @field:NotBlank
    val apiModelName: String,
    val description: String? = null,
    val alias: String? = null,
    val priority: Int = 0,
    val temperature: Double? = null,
    val maxTokens: Int? = null,
)
