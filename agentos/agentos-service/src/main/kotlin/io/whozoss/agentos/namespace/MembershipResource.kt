package io.whozoss.agentos.namespace

import io.swagger.v3.oas.annotations.media.Schema
import jakarta.validation.constraints.NotBlank

/**
 * HTTP resource (DTO) for namespace membership endpoints.
 *
 * This class represents the API contract for managing user roles within a namespace.
 * It is intentionally separate from the internal [MembershipInfo][io.whozoss.agentos.auth.MembershipInfo]
 * domain model so the two can evolve independently.
 *
 * [userId] and [role] are required for POST/PUT (creation and update).
 * [grantedAt] and [grantedBy] are read-only fields populated by the server.
 *
 * [role] is a String that the controller validates against the [NamespaceRole]
 * enum — keeping validation in the controller avoids coupling the DTO to SDK types.
 *
 * Annotated with @Schema(name = "Membership") so that the generated OpenAPI spec
 * keeps the schema name "Membership" instead of "MembershipResource".
 */
@Schema(name = "Membership")
data class MembershipResource(
    @field:NotBlank(message = "userId is required")
    val userId: String? = null,
    @field:NotBlank(message = "role is required")
    val role: String? = null,
    val grantedAt: String? = null,
    val grantedBy: String? = null,
)
