package io.whozoss.agentos.integrationConfig

import com.fasterxml.jackson.databind.JsonNode
import io.swagger.v3.oas.annotations.media.Schema
import jakarta.validation.constraints.NotBlank
import jakarta.validation.constraints.NotNull
import java.util.UUID

/**
 * HTTP resource (DTO) for [IntegrationConfig] entities.
 *
 * Represents the API contract for integration-config endpoints, kept separate from
 * the domain entity so the two can evolve independently.
 *
 * Annotated with @Schema(name = "IntegrationConfig") so the generated OpenAPI spec
 * uses the clean name instead of "IntegrationConfigResource".
 */
@Schema(name = "IntegrationConfig")
data class IntegrationConfigResource(
    val id: UUID? = null,
    @field:NotNull(message = "namespaceId must not be null")
    val namespaceId: UUID?,
    @field:NotBlank(message = "name must not be blank")
    val name: String,
    @field:NotBlank(message = "integrationType must not be blank")
    val integrationType: String,
    val parameters: JsonNode? = null,
)
