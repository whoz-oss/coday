package io.whozoss.agentos.aiProvider

import io.swagger.v3.oas.annotations.media.Schema
import io.whozoss.agentos.sdk.aiProvider.AiApiType
import jakarta.validation.constraints.NotBlank
import jakarta.validation.constraints.NotNull
import java.util.UUID

/**
 * HTTP resource (DTO) for [AiProvider] entities.
 *
 * **Triple-mode contract** — both [namespaceId] and [userId] are nullable. The cross-field
 * invariant `(namespaceId != null) OR (userId != null)` is enforced server-side by
 * [AiProviderServiceImpl.requireScope] and surfaces as HTTP 400 — Bean Validation cannot
 * express an "either / or" across two fields. The `(namespaceId, userId)` pair drives the
 * implicit-scope dispatch on `POST` (Decision 15) :
 *  - `(ns, null)` → NS-shared ; `(null, user)` → user-global ; `(ns, user)` → user × ns ;
 *  - both null → 400 ; `userId` mismatched with the principal → 400.
 *
 * [apiKey] is write-only in practice: on read it is always returned masked.
 * On write, if the value contains the mask sentinel "****", the controller treats
 * it as "unchanged" and preserves the persisted key.
 *
 * Models are managed as independent `AiModel` entities via their own endpoints — they
 * are not embedded in this resource.
 */
@Schema(name = "AiProvider")
data class AiProviderResource(
    val id: UUID? = null,
    // OpenAPI 3.1 nullable encoding : `type: [string, "null"]`. The simpler `nullable = true`
    // attribute is OpenAPI 3.0-only and silently dropped by SpringDoc 2.x in 3.1 mode, leaving
    // generated SDKs typing the field as `string | undefined` instead of `string | null` (F3).
    @field:Schema(types = ["string", "null"], format = "uuid")
    val namespaceId: UUID? = null,
    @field:Schema(types = ["string", "null"], format = "uuid")
    val userId: UUID? = null,
    @field:NotBlank
    val name: String,
    val description: String? = null,
    @field:NotNull
    val apiType: AiApiType?,
    val baseUrl: String? = null,
    val apiKey: String? = null,
    val headers: Map<String, String>? = null,
)
