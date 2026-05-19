package io.whozoss.agentos.integrationConfig

import com.fasterxml.jackson.databind.JsonNode
import io.swagger.v3.oas.annotations.media.Schema
import jakarta.validation.constraints.NotBlank
import java.util.UUID

/**
 * HTTP resource (DTO) for [IntegrationConfig] entities.
 *
 * **Triple-mode contract** (post-unification with Epic 6 user-overlay support) :
 * - Both [namespaceId] and [userId] are nullable. The cross-field invariant
 *   `(namespaceId != null) OR (userId != null)` is enforced by
 *   [IntegrationConfigServiceImpl.requireScope] and surfaces as HTTP 400 — Bean
 *   Validation cannot express an "either / or" across two fields.
 * - On `POST`, the controller infers the scope from the `(namespaceId, userId)`
 *   payload pair (Decision 15, implicit dispatch). [userId] is treated as an
 *   intent flag *and* a mass-assignment guard : the controller validates that the
 *   client-supplied [userId] matches the authenticated principal before persisting.
 * - On `PUT`, [userId] is preserved from the persisted row (mass-assignment guard).
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
    // SpringDoc 2.x workaround — see the matching note on AiProviderResource.
    @field:Schema(types = ["string", "null"], format = "uuid")
    val namespaceId: UUID? = null,
    @field:Schema(types = ["string", "null"], format = "uuid")
    val userId: UUID? = null,
    @field:NotBlank
    val name: String,
    @field:NotBlank
    val integrationType: String,
    val description: String? = null,
    val parameters: JsonNode? = null,
)
