package io.whozoss.agentos.userGroup

import java.util.UUID

/**
 * A user's membership in a [UserGroup], with their role on that group.
 *
 * [role] is either [MEMBER] or [ADMIN]. The distinction matters for admin UI:
 * an ADMIN can manage the group's members and agent assignments; a MEMBER can only
 * use the agents deployed to the group.
 *
 * Both [:MEMBER] and [:ADMIN] graph edges are unified through [PermissionService] —
 * the edge type is the role, there is always at most one edge per (user, group) pair.
 */
data class UserGroupMember(
    val userId: UUID,
    val externalId: String,
    val email: String,
    val role: String,
) {
    companion object {
        const val ROLE_MEMBER = "MEMBER"
        const val ROLE_ADMIN = "ADMIN"
    }
}
