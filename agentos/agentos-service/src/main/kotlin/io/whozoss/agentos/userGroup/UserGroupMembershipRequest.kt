package io.whozoss.agentos.userGroup

import io.swagger.v3.oas.annotations.media.Schema
import jakarta.validation.constraints.Pattern
import java.util.UUID

/**
 * One entry in a membership update batch.
 *
 * [role] values:
 * - `"ADMIN"` — grant or promote to ADMIN (revokes any existing MEMBER edge first)
 * - `"MEMBER"` — grant or demote to MEMBER (revokes any existing ADMIN edge first)
 * - `null` — remove the user from the group entirely (revokes both edges)
 */
@Schema(name = "UserGroupMembershipEntry")
data class UserGroupMembershipEntry(
    val userId: UUID,
    @field:Pattern(regexp = "ADMIN|MEMBER", message = "role must be ADMIN or MEMBER")
    val role: String?,
)
