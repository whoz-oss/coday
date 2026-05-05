package io.whozoss.agentos.user

import io.swagger.v3.oas.annotations.media.Schema
import jakarta.validation.constraints.Email
import java.util.UUID

/**
 * HTTP resource (DTO) for [User] entities.
 *
 * This class represents the API contract for user endpoints. It is intentionally
 * separate from the [User] domain entity so the two can evolve independently.
 *
 * Validation annotations live here, not on [User], keeping the domain model clean.
 *
 * [email] is an optional profile field — nullable so that users created in local mode
 * (where no email is known at creation time) are not forced to provide one.
 * The @Email constraint only fires when the value is non-null.
 *
 * [externalId] is a read-only server-managed field (IdP key). HTTP clients may receive
 * it in responses but it is never written from the request body.
 *
 * [isAdmin] is nullable on the request body so PATCH-like PUT (omitting the field)
 * preserves the persisted value instead of silently demoting. On responses, the
 * server always sets a non-null value derived from [User.isAdmin].
 *
 * Annotated with @Schema(name = "User") so that the generated OpenAPI spec
 * keeps the schema name "User" instead of "UserResource".
 */
@Schema(name = "User")
data class UserResource(
    val id: UUID? = null,
    @field:Email(message = "email must be a valid address")
    val email: String? = null,
    val externalId: String? = null,
    val firstname: String? = null,
    val lastname: String? = null,
    val bio: String? = null,
    val isAdmin: Boolean? = null,
)
