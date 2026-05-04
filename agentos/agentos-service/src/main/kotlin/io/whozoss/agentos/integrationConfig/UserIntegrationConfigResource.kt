package io.whozoss.agentos.integrationConfig

import com.fasterxml.jackson.databind.JsonNode
import io.swagger.v3.oas.annotations.media.Schema
import jakarta.validation.constraints.NotBlank
import java.util.UUID

/**
 * HTTP resource (DTO) for user-scoped [IntegrationConfig] entities.
 *
 * Distinct from [IntegrationConfigResource] in two ways:
 * - [namespaceId] is genuinely nullable (no @NotNull) — `null` means user-global
 *   (the row applies cross-namespace), a UUID means user × namespace.
 * - [userId] is exposed for round-tripping but is **ignored on writes** —
 *   the controller forces `userId = auth.name` (mass-assignment guard, FR19/AR6).
 *
 * Authorization is **ownership-based** (`cfg.userId == auth.name`), enforced by
 * [UserIntegrationConfigGuard], not by `@PreAuthorize("hasPermission(...)")`.
 *
 * TODO: [parameters] may contain sensitive credentials (API keys, tokens). Currently returned
 *   in clear text. A future iteration should mask secrets in API responses.
 */
@Schema(name = "UserIntegrationConfig")
data class UserIntegrationConfigResource(
    val id: UUID? = null,
    val namespaceId: UUID? = null,
    val userId: UUID? = null,
    @field:NotBlank
    val name: String,
    @field:NotBlank
    val integrationType: String,
    val description: String? = null,
    val parameters: JsonNode? = null,
)
