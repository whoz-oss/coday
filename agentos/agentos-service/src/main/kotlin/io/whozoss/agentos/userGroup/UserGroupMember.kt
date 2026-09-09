package io.whozoss.agentos.userGroup

import java.util.UUID

/**
 * A user linked to a UserGroup by a `[:MEMBER]` or `[:ADMIN]` relationship.
 *
 * [externalId] is the key the create/update requests use to add/remove members and designate admins;
 * [role] is the user's relation to the group (`ADMIN` = can manage the group, `MEMBER` = read), the
 * rest are display fields.
 */
data class UserGroupMember(
    val userId: UUID,
    val externalId: String,
    val role: String,
    val email: String?,
    val firstname: String?,
    val lastname: String?,
)
