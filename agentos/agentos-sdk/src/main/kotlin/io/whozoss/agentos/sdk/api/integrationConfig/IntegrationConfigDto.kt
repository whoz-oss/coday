package io.whozoss.agentos.sdk.api.integrationConfig

import com.fasterxml.jackson.annotation.JsonIgnoreProperties
import com.fasterxml.jackson.databind.JsonNode
import io.swagger.v3.oas.annotations.media.Schema
import jakarta.validation.constraints.NotBlank
import java.util.UUID

/**
 * HTTP DTO for IntegrationConfig entities — used as both request body and response body
 * on the `/api/integration-configs` endpoints.
 *
 * At least one of [namespaceId] / [userId] must be non-null. This constraint is
 * enforced server-side; callers that supply neither will receive HTTP 400.
 *
 * Scope semantics (same as [io.whozoss.agentos.sdk.api.aiProvider.AiProviderDto]):
 * - `(namespaceId, null)` → namespace-shared config
 * - `(null, userId)`      → user-global config
 * - `(namespaceId, userId)` → user × namespace override
 *
 * [parameters] is an opaque JSON node containing integration-specific configuration
 * (API keys, tokens, URLs, etc.). The schema for [parameters] is defined per
 * [integrationType] and can be retrieved from `GET /api/integration-types/{type}`.
 *
 * Note: [parameters] may contain sensitive credentials. Callers should treat the
 * value as write-only and not store or log it unnecessarily.
 */
@Schema(name = "IntegrationConfig")
@JsonIgnoreProperties(ignoreUnknown = true)
data class IntegrationConfigDto(
    val id: UUID? = null,
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
    val authSettingName: String? = null,
)
