package io.whozoss.agentos.namespace

import io.swagger.v3.oas.annotations.media.Schema
import jakarta.validation.constraints.NotBlank
import java.util.UUID

/**
 * HTTP resource (DTO) for [Namespace] entities.
 *
 * Annotated with @Schema(name = "Namespace") so that the generated OpenAPI spec
 * keeps the schema name "Namespace" instead of "NamespaceResource".
 */
@Schema(name = "Namespace")
data class NamespaceResource(
    val id: UUID? = null,
    @field:NotBlank(message = "name must not be blank")
    val name: String,
    val description: String? = null,
    @Schema(description = "Optional filesystem path to a directory containing base configuration for this namespace (agents, tools, etc.)")
    val configPath: String? = null,
)
