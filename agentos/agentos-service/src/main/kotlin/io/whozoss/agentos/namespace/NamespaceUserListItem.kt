package io.whozoss.agentos.namespace

import io.swagger.v3.oas.annotations.media.Schema
import java.util.UUID

/**
 * HTTP resource returned by `GET /api/namespaces/{id}/users` (Story 2.5).
 *
 * Represents a user holding a direct `[:ADMIN]` or `[:MEMBER]` relation on the
 * namespace. Super-admins are not listed here unless they also hold a direct
 * relation — this endpoint surfaces namespace-level grants, not system-level roles.
 *
 * Users with both ADMIN and MEMBER relations appear once with `role = "ADMIN"`.
 */
@Schema(name = "NamespaceUserListItem")
data class NamespaceUserListItem(
    val id: UUID,
    val externalId: String,
    val email: String,
    val firstname: String? = null,
    val lastname: String? = null,
    @Schema(description = "The user's direct role on this namespace", allowableValues = ["ADMIN", "MEMBER"])
    val role: String,
)
