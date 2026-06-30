package io.whozoss.agentos.prompt

import io.swagger.v3.oas.annotations.media.Schema
import jakarta.validation.constraints.NotBlank
import jakarta.validation.constraints.NotEmpty
import java.util.UUID

/**
 * HTTP resource (DTO) for [Prompt] entities.
 *
 * [namespaceId] is null for platform-level prompts and non-null for namespace-scoped prompts.
 *
 * On POST, scope is inferred from [namespaceId]:
 * - null  -> platform (Super Admin only)
 * - present -> namespace-scoped (WRITE permission on namespace required)
 *
 * On PUT, [namespaceId] is immutable (preserved from the persisted entity).
 *
 * Annotated with @Schema(name = "Prompt") so the generated OpenAPI spec uses the clean
 * name instead of "PromptResource".
 */
@Schema(name = "Prompt")
data class PromptResource(
    val id: UUID? = null,
    // SpringDoc 2.x workaround: explicit schema types for nullable UUID.
    @field:Schema(types = ["string", "null"], format = "uuid")
    val namespaceId: UUID? = null,
    @field:NotBlank val name: String,
    val description: String? = null,
    @field:NotEmpty val content: List<String>,
    val parameters: List<PromptParameterResource> = emptyList(),
)

@Schema(name = "PromptParameter")
data class PromptParameterResource(
    @field:NotBlank val name: String,
    val description: String? = null,
    val defaultValue: String? = null,
)
