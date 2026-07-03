package io.whozoss.agentos.prompt

import com.fasterxml.jackson.annotation.JsonInclude
import io.swagger.v3.oas.annotations.media.Schema
import jakarta.validation.Valid
import jakarta.validation.constraints.NotBlank
import jakarta.validation.constraints.NotEmpty
import java.time.Instant
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
 * [createdBy], [createdOn], [updatedBy], [updatedOn] are read-only audit fields populated
 * by Spring Data auditing. They are ignored on write (POST/PUT) and only present in
 * GET responses.
 *
 * Annotated with @Schema(name = "Prompt") so the generated OpenAPI spec uses the clean
 * name instead of "PromptResource".
 */
@Schema(name = "Prompt")
@JsonInclude(JsonInclude.Include.NON_NULL)
data class PromptResource(
    val id: UUID? = null,
    // SpringDoc 2.x workaround: explicit schema types for nullable UUID.
    @field:Schema(types = ["string", "null"], format = "uuid")
    val namespaceId: UUID? = null,
    @field:Schema(types = ["string", "null"], format = "uuid")
    val userId: UUID? = null,
    @field:NotBlank val name: String,
    val description: String? = null,
    @field:NotEmpty val content: List<@NotBlank String>,
    @field:Valid val parameters: List<PromptParameterResource> = emptyList(),
    // Read-only audit fields — populated from EntityMetadata on GET responses,
    // ignored on POST/PUT (Spring Data auditing sets them server-side).
    val createdBy: String? = null,
    val createdOn: Instant? = null,
    val updatedBy: String? = null,
    val updatedOn: Instant? = null,
)

@Schema(name = "PromptParameter")
data class PromptParameterResource(
    @field:NotBlank val name: String,
    val description: String? = null,
    @field:NotBlank val defaultValue: String,
)
