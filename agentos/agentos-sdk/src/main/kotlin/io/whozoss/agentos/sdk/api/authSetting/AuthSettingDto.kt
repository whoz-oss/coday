package io.whozoss.agentos.sdk.api.authSetting

import com.fasterxml.jackson.annotation.JsonIgnoreProperties
import io.swagger.v3.oas.annotations.media.Schema
import io.whozoss.agentos.sdk.authSetting.AuthType
import jakarta.validation.constraints.NotBlank
import jakarta.validation.constraints.NotNull
import java.util.UUID

/**
 * HTTP DTO for AuthSetting entities — used as both request body and response body on
 * the `/api/auth-settings` endpoints.
 *
 * At least one of [namespaceId] / [userId] must be non-null. This constraint is
 * enforced server-side; callers that supply neither will receive HTTP 400.
 *
 * [data] values are write-only in practice: on read, all values are returned masked (`"****"`).
 * On write, if a value contains the mask sentinel `"****"`, the server treats it as
 * "unchanged" and preserves the persisted value. A null map or absent key means "preserve",
 * an empty string value means "clear", and a new non-sentinel value means "replace".
 *
 * [authType] is immutable post-create — sending a different value on update is rejected
 * with HTTP 400.
 *
 * Scope semantics:
 * - `(namespaceId, null)`      → namespace-shared auth setting
 * - `(null, userId)`           → user-global auth setting
 * - `(namespaceId, userId)`    → user × namespace override
 */
@Schema(name = "AuthSetting")
@JsonIgnoreProperties(ignoreUnknown = true)
data class AuthSettingDto(
    val id: UUID? = null,
    @field:Schema(types = ["string", "null"], format = "uuid")
    val namespaceId: UUID? = null,
    @field:Schema(types = ["string", "null"], format = "uuid")
    val userId: UUID? = null,
    @field:NotBlank
    val name: String,
    val description: String? = null,
    @field:NotNull
    val authType: AuthType?,
    val data: Map<String, String>? = null,
)
