package io.whozoss.agentos.sdk.api.user

import com.fasterxml.jackson.annotation.JsonIgnoreProperties
import io.swagger.v3.oas.annotations.media.Schema
import jakarta.validation.constraints.Pattern
import java.util.UUID

/**
 * A single (userId, role) entry expressing a user's membership in any entity that
 * supports role-based access (Namespace, UserGroup, …).
 *
 * A `null` [role] means **revoke**: the user loses every relation they currently hold
 * on the target entity. A non-null [role] is the desired target role — the service is
 * responsible for translating the string value to the appropriate
 * [io.whozoss.agentos.permissions.PermissionRelation].
 *
 * Bean Validation: [role] accepts null (revoke) or one of the allowed role values.
 * The `@Pattern` regexp uses a negative lookahead so null passes through untouched —
 * Bean Validation skips `@Pattern` on null by spec, so null is always valid here.
 */
@JsonIgnoreProperties(ignoreUnknown = true)
data class UserMembershipRole(
    @field:Schema(description = "Internal user id.")
    val userId: UUID,
    @field:Schema(
        description = "Target role for this user on the entity. Null means revoke all relations.",
        nullable = true,
        allowableValues = ["ADMIN", "MEMBER"],
    )
    @field:Pattern(
        regexp = "ADMIN|MEMBER",
        message = "role must be ADMIN, MEMBER, or null",
    )
    val role: String? = null,
)
