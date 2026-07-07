package io.whozoss.agentos.sdk.api.aiProvider

import com.fasterxml.jackson.annotation.JsonIgnoreProperties
import io.swagger.v3.oas.annotations.media.Schema
import io.whozoss.agentos.sdk.aiProvider.AiApiType
import jakarta.validation.constraints.NotBlank
import jakarta.validation.constraints.NotNull
import java.util.UUID

/**
 * HTTP DTO for AiProvider entities — used as both request body and response body on
 * the `/api/ai-providers` endpoints.
 *
 * At least one of [namespaceId] / [userId] must be non-null. This constraint is
 * enforced server-side; callers that supply neither will receive HTTP 400.
 *
 * [apiKey] is write-only in practice: on read it is always returned masked (`"****"`).
 * On write, if the value contains the mask sentinel `"****"`, the server treats it as
 * "unchanged" and preserves the persisted key.
 *
 * Scope semantics:
 * - `(namespaceId, null)` → namespace-shared provider config
 * - `(null, userId)`      → user-global provider config
 * - `(namespaceId, userId)` → user × namespace override
 */
@Schema(name = "AiProvider")
@JsonIgnoreProperties(ignoreUnknown = true)
data class AiProviderDto(
    val id: UUID? = null,
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
