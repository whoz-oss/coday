package io.whozoss.agentos.user

import io.swagger.v3.oas.annotations.media.Schema
import jakarta.validation.constraints.Email
import jakarta.validation.constraints.NotBlank
import java.util.UUID

/**
 * HTTP resource (DTO) for [User] entities.
 *
 * This class represents the API contract for user endpoints. It is intentionally
 * separate from the [User] domain entity so the two can evolve independently.
 *
 * Validation annotations live here, not on [User], keeping the domain model clean.
 *
 * [externalId] is write-once on creation and is not exposed in responses —
 * it is an internal identity-provider key that HTTP clients have no need to see.
 *
 * Annotated with @Schema(name = "User") so that the generated OpenAPI spec
 * keeps the schema name "User" instead of "UserResource".
 */
@Schema(name = "User")
data class UserResource(
    val id: UUID? = null,
    @field:NotBlank(message = "email must not be blank")
    @field:Email(message = "email must be a valid address")
    val email: String,
    val externalId: String? = null,
    val firstname: String? = null,
    val lastname: String? = null,
    val bio: String? = null,
)
