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
 *
 * TODO: [parameters] may contain sensitive credentials (API keys, tokens). Currently returned
 *   in clear text. A future iteration should mask secrets in API responses.
 */
@Schema(name = "IntegrationConfig")
data class IntegrationConfigResource(
    val id: UUID? = null,
    @field:NotNull
    val namespaceId: UUID?,
    @field:NotBlank
    val name: String,
    @field:NotBlank
    val integrationType: String,
    val description: String? = null,
    val parameters: JsonNode? = null,
)
