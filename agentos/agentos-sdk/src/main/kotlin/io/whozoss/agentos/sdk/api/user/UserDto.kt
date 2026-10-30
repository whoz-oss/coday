package io.whozoss.agentos.sdk.api.user

import com.fasterxml.jackson.annotation.JsonIgnoreProperties
import io.swagger.v3.oas.annotations.media.Schema
import jakarta.validation.constraints.Email
import jakarta.validation.constraints.Pattern
import java.util.UUID

/**
 * HTTP DTO for User entities — used as both request body and response body on
 * the `/api/users` endpoints.
 *
 * [externalId] is a read-only server-managed field (IdP key). HTTP clients may receive
 * it in responses but it is never written from the request body.
 *
 * [isAdmin] follows PUT replace-semantics: clients are expected to send the full state
 * on update. Omitting the field on PUT will reset it to `false`.
 */
@Schema(name = "User")
@JsonIgnoreProperties(ignoreUnknown = true)
data class UserDto(
    val id: UUID? = null,
    @field:Email(message = "email must be a valid address")
    val email: String? = null,
    val externalId: String? = null,
    val firstname: String? = null,
    val lastname: String? = null,
    val bio: String? = null,
    val isAdmin: Boolean = false,
    /**
     * BCP 47 language tag (e.g. "en", "fr", "en-US", "zh-Hant-TW").
     * Null means no preference set — callers will fall back to English.
     * Structural validation only: the regex enforces BCP 47 shape but does not
     * verify that the language subtag is a real ISO 639 code.
     */
    @field:Pattern(
        regexp = "^[a-zA-Z]{2,3}(-[a-zA-Z0-9]{2,8})*$",
        message = "preferredLanguage must be a valid BCP 47 language tag (e.g. \"en\", \"fr\", \"en-US\")",
    )
    val preferredLanguage: String? = null,
)
