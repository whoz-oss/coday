package io.whozoss.agentos.aiProvider

import io.swagger.v3.oas.annotations.media.Schema
import io.whozoss.agentos.sdk.aiProvider.AiApiType
import jakarta.validation.constraints.NotBlank
import jakarta.validation.constraints.NotNull
import java.util.UUID

/**
 * HTTP resource (DTO) for user-scoped [io.whozoss.agentos.sdk.aiProvider.AiProvider] entities.
 *
 * Distinct from [AiProviderResource]:
 * - [namespaceId] is genuinely nullable (no @NotNull) — null means user-global.
 * - [userId] is ignored on writes (mass-assignment guard, FR19/AR6); forced to `auth.name` on create.
 * - [apiType] is immutable after creation (preserved via `existing.copy(...)` on update).
 *
 * [apiKey] is write-only in practice: on read it is returned masked via [maskApiKey].
 * On write, if the value contains the mask sentinel "****", the controller preserves
 * the persisted key ([isMasked] check in [UserAiProviderController.resolveApiKey]).
 */
@Schema(name = "UserAiProvider")
data class UserAiProviderResource(
    val id: UUID? = null,
    val namespaceId: UUID? = null,
    val userId: UUID? = null,
    @field:NotBlank
    val name: String,
    val description: String? = null,
    @field:NotNull
    val apiType: AiApiType?,
    val baseUrl: String? = null,
    val apiKey: String? = null,
)
