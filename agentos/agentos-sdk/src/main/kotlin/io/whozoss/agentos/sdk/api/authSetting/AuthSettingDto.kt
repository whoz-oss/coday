package io.whozoss.agentos.sdk.api.authSetting

import com.fasterxml.jackson.annotation.JsonIgnoreProperties
import com.fasterxml.jackson.annotation.JsonSubTypes
import com.fasterxml.jackson.annotation.JsonTypeInfo
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
 * Each subtype carries typed, nullable properties for its auth-specific fields.
 * All specific properties are [String?] because:
 * - On reads, sensitive values are returned masked (`"****"` or partial mask).
 * - On writes (PUT), a masked sentinel value means “preserve existing”; null means
 *   “preserve existing”; empty string means “clear”; a new non-sentinel value means “replace”.
 *
 * [authType] is immutable post-create — sending a different value on update is rejected
 * with HTTP 400.
 *
 * Scope semantics:
 * - `(namespaceId, null)`      → namespace-shared auth setting
 * - `(null, userId)`           → user-global auth setting
 * - `(namespaceId, userId)`    → user × namespace override
 *
 * Jackson polymorphism: [authType] is used as the discriminant, mirroring the domain
 * sealed hierarchy.
 */
@JsonIgnoreProperties(ignoreUnknown = true)
@JsonTypeInfo(
    use = JsonTypeInfo.Id.NAME,
    include = JsonTypeInfo.As.EXISTING_PROPERTY,
    property = "authType",
)
@JsonSubTypes(
    JsonSubTypes.Type(value = ApiKeyAuthSettingDto::class, name = "API_KEY"),
    JsonSubTypes.Type(value = BearerTokenAuthSettingDto::class, name = "BEARER_TOKEN"),
    JsonSubTypes.Type(value = BasicAuthAuthSettingDto::class, name = "BASIC_AUTH"),
    JsonSubTypes.Type(value = OAuthDiscoverableAuthSettingDto::class, name = "OAUTH_DISCOVERABLE"),
    JsonSubTypes.Type(value = OAuthRegisteredAuthSettingDto::class, name = "OAUTH_REGISTERED"),
    JsonSubTypes.Type(value = OAuthCustomAuthSettingDto::class, name = "OAUTH_CUSTOM"),
)
sealed interface AuthSettingDto {
    val id: UUID?
    val namespaceId: UUID?
    val userId: UUID?
    val name: String
    val description: String?

    @get:NotNull
    val authType: AuthType?
}

// ---------------------------------------------------------------------------
// Subtypes
// ---------------------------------------------------------------------------

@Schema(name = "ApiKeyAuthSetting")
@JsonIgnoreProperties(ignoreUnknown = true)
data class ApiKeyAuthSettingDto(
    override val id: UUID? = null,
    @field:Schema(types = ["string", "null"], format = "uuid")
    override val namespaceId: UUID? = null,
    @field:Schema(types = ["string", "null"], format = "uuid")
    override val userId: UUID? = null,
    @field:NotBlank
    override val name: String,
    override val description: String? = null,
    override val authType: AuthType? = AuthType.API_KEY,
    val apiKey: String? = null,
) : AuthSettingDto

@Schema(name = "BearerTokenAuthSetting")
@JsonIgnoreProperties(ignoreUnknown = true)
data class BearerTokenAuthSettingDto(
    override val id: UUID? = null,
    @field:Schema(types = ["string", "null"], format = "uuid")
    override val namespaceId: UUID? = null,
    @field:Schema(types = ["string", "null"], format = "uuid")
    override val userId: UUID? = null,
    @field:NotBlank
    override val name: String,
    override val description: String? = null,
    override val authType: AuthType? = AuthType.BEARER_TOKEN,
    val token: String? = null,
) : AuthSettingDto

@Schema(name = "BasicAuthAuthSetting")
@JsonIgnoreProperties(ignoreUnknown = true)
data class BasicAuthAuthSettingDto(
    override val id: UUID? = null,
    @field:Schema(types = ["string", "null"], format = "uuid")
    override val namespaceId: UUID? = null,
    @field:Schema(types = ["string", "null"], format = "uuid")
    override val userId: UUID? = null,
    @field:NotBlank
    override val name: String,
    override val description: String? = null,
    override val authType: AuthType? = AuthType.BASIC_AUTH,
    val username: String? = null,
    val password: String? = null,
) : AuthSettingDto

@Schema(name = "OAuthDiscoverableAuthSetting")
@JsonIgnoreProperties(ignoreUnknown = true)
data class OAuthDiscoverableAuthSettingDto(
    override val id: UUID? = null,
    @field:Schema(types = ["string", "null"], format = "uuid")
    override val namespaceId: UUID? = null,
    @field:Schema(types = ["string", "null"], format = "uuid")
    override val userId: UUID? = null,
    @field:NotBlank
    override val name: String,
    override val description: String? = null,
    override val authType: AuthType? = AuthType.OAUTH_DISCOVERABLE,
    val discoveryUrl: String? = null,
    val clientId: String? = null,
    val clientSecret: String? = null,
    val scopes: String? = null,
) : AuthSettingDto

@Schema(name = "OAuthRegisteredAuthSetting")
@JsonIgnoreProperties(ignoreUnknown = true)
data class OAuthRegisteredAuthSettingDto(
    override val id: UUID? = null,
    @field:Schema(types = ["string", "null"], format = "uuid")
    override val namespaceId: UUID? = null,
    @field:Schema(types = ["string", "null"], format = "uuid")
    override val userId: UUID? = null,
    @field:NotBlank
    override val name: String,
    override val description: String? = null,
    override val authType: AuthType? = AuthType.OAUTH_REGISTERED,
    val clientId: String? = null,
    val clientSecret: String? = null,
    val authorizationUrl: String? = null,
    val tokenUrl: String? = null,
    val scopes: String? = null,
) : AuthSettingDto

@Schema(name = "OAuthCustomAuthSetting")
@JsonIgnoreProperties(ignoreUnknown = true)
data class OAuthCustomAuthSettingDto(
    override val id: UUID? = null,
    @field:Schema(types = ["string", "null"], format = "uuid")
    override val namespaceId: UUID? = null,
    @field:Schema(types = ["string", "null"], format = "uuid")
    override val userId: UUID? = null,
    @field:NotBlank
    override val name: String,
    override val description: String? = null,
    override val authType: AuthType? = AuthType.OAUTH_CUSTOM,
    val clientId: String? = null,
    val clientSecret: String? = null,
    val authorizationUrl: String? = null,
    val tokenUrl: String? = null,
    val scopes: String? = null,
) : AuthSettingDto
